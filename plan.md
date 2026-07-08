
## Problem Statement

Build a production-quality, from-scratch Rate Limiter in Java/Spring Boot using
Test-Driven Development (TDD) and SOLID principles. No external rate-limiting
libraries. Implemented as a Spring `HandlerInterceptor` using the Sliding Window
Log algorithm, with clients identified by the `X-API-Key` request header.

---

## Design Decisions

| Concern | Choice | Rationale |
|---|---|---|
| Spring Mechanism | `HandlerInterceptor` | Native per-path config via `addInterceptors()`, cleaner for MVC endpoints, has handler context post-DispatcherServlet |
| Algorithm | Sliding Window Log | Most accurate, no boundary burst, maps directly to spec's `ConcurrentHashMap<String, List<Long>>` |
| Client Identification | `X-API-Key` header | Trivial independent-client testing (`X-API-Key: alice` vs `X-API-Key: bob`), realistic API scenario, spec defines HTTP 400 for missing key |
| State Store | `ConcurrentHashMap` (in-memory) | Spec requirement — no Redis, no database |
| Memory Cleanup | `@Scheduled` every 60s | Predictable, non-blocking — avoids latency spikes on hot-path vs lazy eviction |

---

## Algorithm: Sliding Window Log

### How It Works
1. Each client+endpoint pair stores a `List<Long>` of request timestamps (epoch ms)
2. On every request:
   - Evict all timestamps older than `now - windowSizeInSeconds`
   - Count remaining timestamps — that is the current usage
   - If `count < limit` → add current timestamp, allow request
   - If `count >= limit` → reject with HTTP 429
3. `resetAt` = timestamp of the oldest entry in the window + `windowSizeInSeconds`

### Pros
- No boundary burst (unlike Fixed Window where 2× requests can slip through at window edges)
- Accurate per-client, per-endpoint tracking
- Simple eviction logic

### Cons
- Memory proportional to request volume (every request stores a timestamp)
- Mitigated by scheduled cleanup of expired entries

### Comparison with Other Algorithms

| | Fixed Window Counter | Sliding Window Log | Token Bucket |
|---|---|---|---|
| **Accuracy** | Low — burst at boundary | High | Medium-High |
| **Memory** | Low — one counter | High — full log | Low — one bucket |
| **Complexity** | Simple | Medium | Medium |
| **Main Weakness** | 2× burst at window edge | Memory growth | Burst by design |

---

## SOLID Principles Mapping

| Principle | Application |
|---|---|
| **S** — Single Responsibility | `RateLimiterService` manages window logic only. `RateLimitInterceptor` handles HTTP concerns only. `ApiController` handles responses only. |
| **O** — Open/Closed | `RateLimitConfig` drives limits via `application.properties` — new endpoints added via config, not code changes |
| **L** — Liskov Substitution | `SlidingWindowRateLimiterService` implements `RateLimiter` interface — algorithm swappable without changing interceptor |
| **I** — Interface Segregation | `RateLimiter` interface exposes only `checkAndRecord()` and `getStatus()` — nothing extra |
| **D** — Dependency Inversion | `RateLimitInterceptor` depends on the `RateLimiter` interface, not the concrete service implementation |

---

## Architecture Diagram

```mermaid
graph TD
    A[HTTP Request] --> B{X-API-Key present?}
    B -- No --> C[400 Bad Request]
    B -- Yes --> D[RateLimitInterceptor.preHandle]
    D --> E[RateLimiterService.checkAndRecord]
    E --> F{Sliding Window Log}
    F -- Evict old timestamps --> G[Count remaining]
    G -- within limit --> H[Add timestamp + set headers + pass through]
    G -- over limit --> I[429 Too Many Requests + Retry-After]
    H --> J[ApiController]
    J --> K[200 OK Response]

    subgraph RateLimiterService
        F
        G
    end

    subgraph Scheduled Cleanup
        L[@Scheduled every 60s] --> M[Remove fully-expired client entries]
    end
```

---

## Project Structure

```
src/
├── main/
│   ├── java/com/chegg/ratelimiter/
│   │   ├── RateLimiterApplication.java
│   │   ├── controller/
│   │   │   └── ApiController.java
│   │   ├── interceptor/
│   │   │   └── RateLimitInterceptor.java
│   │   ├── service/
│   │   │   ├── RateLimiter.java                   ← interface (SOLID: DIP)
│   │   │   └── SlidingWindowRateLimiterService.java
│   │   ├── config/
│   │   │   ├── WebConfig.java
│   │   │   └── RateLimitConfig.java
│   │   └── dto/
│   │       ├── RateLimitResult.java
│   │       ├── RateLimitStatus.java
│   │       ├── StatusResponse.java
│   │       └── ErrorResponse.java
│   └── resources/
│       └── application.properties
└── test/
    └── java/com/chegg/ratelimiter/
        ├── service/
        │   └── RateLimiterServiceTest.java
        └── controller/
            └── ApiControllerTest.java
```

---

## Demo API Endpoints

| Endpoint | Method | Description | Rate Limit |
|---|---|---|---|
| `/api/general` | GET | General-purpose endpoint | 20 req / 60 sec |
| `/api/submit` | POST | Write/submission endpoint | 5 req / 60 sec |
| `/api/status` | GET | Caller's current rate limit state | 60 req / 60 sec |

### Response Formats

**`GET /api/status` — 200 OK:**
```json
{
  "limit": 60,
  "remaining": 57,
  "resetAt": 1750000000
}
```

**All other endpoints — 200 OK:**
```json
{ "message": "OK" }
```

**Any endpoint — 429 Too Many Requests:**
```json
{
  "error": "Too many requests",
  "retryAfterSeconds": 42
}
```

### Response Headers (on every request)

| Header | Description |
|---|---|
| `X-RateLimit-Limit` | Configured max requests per window |
| `X-RateLimit-Remaining` | Requests remaining in current window |
| `X-RateLimit-Reset` | Unix epoch timestamp when window resets |
| `Retry-After` | Seconds until reset (only on 429 responses) |

### HTTP Status Map

| Scenario | Status |
|---|---|
| Request within limit | 200 + rate limit headers |
| Request exceeds limit | 429 + error body + `Retry-After` |
| Missing `X-API-Key` header | 400 |
| Unexpected server error | 500 |

---

## Test Cases (TDD)

### Unit Tests — `RateLimiterServiceTest`

| # | Test Name | Description | Assertion |
|---|---|---|---|
| T1 | `requestWithinLimit_shouldPass` | Normal request under limit | `allowed=true`, `remaining = limit - 1` |
| T2 | `exactNthRequest_shouldPass` | Request at the exact limit boundary | The `limit`th request is still allowed |
| T3 | `nPlusOneRequest_shouldBeBlocked` | One over the limit | `allowed=false` |
| T4 | `differentClients_haveIndependentCounters` | Two different API keys | Blocking key-A does not affect key-B |
| T5 | `afterWindowExpires_counterResets` | Window reset behavior | Request allowed again after `windowSizeInSeconds` |
| T6 | `resetAt_isFutureTimestamp` | Header accuracy | `resetAt > currentEpochSeconds` |
| T7 | `slidingWindow_evictsOldEntries` | Eviction correctness | Timestamps outside window are not counted |
| T8 | `concurrentRequests_areThreadSafe` | Thread safety | 100 concurrent threads — exactly `limit` pass, rest blocked |

### Integration Tests — `ApiControllerTest` (MockMvc)

| # | Test Name | Description | Assertion |
|---|---|---|---|
| T9 | `generalEndpoint_within limit_returns200WithHeaders` | Normal GET /api/general | 200 + `X-RateLimit-Limit: 20`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` present |
| T10 | `generalEndpoint_exceeds20_returns429` | 21st request to /api/general | 429 + body `{"error":"Too many requests","retryAfterSeconds":N}` |
| T11 | `submitEndpoint_independentOf_generalEndpoint` | Exhaust /api/submit, check /api/general | /api/general unaffected after /api/submit is blocked |
| T12 | `statusEndpoint_returnsCorrectJson` | GET /api/status response body | JSON with `limit`, `remaining`, `resetAt` fields |
| T13 | `missingApiKey_returns400` | No `X-API-Key` header | 400 Bad Request |
| T14 | `submitEndpoint_blocksAt5_beforeGeneralAt20` | Submit hits 429 at request 6 | /api/submit returns 429 at 6th, /api/general still passes |
| T15 | `blockedRequest_hasRetryAfterHeader` | 429 response headers | `Retry-After` header present and value > 0 |
| T16 | `afterWindowReset_clientCanRequestAgain` | Window expiry end-to-end | Using 1s test window + `Clock` injection — request allowed after reset |

---

## Task Breakdown (TDD Order)

### Task 1: Project Scaffold + Domain Interfaces
**Objective:** Maven project, all dependencies, core interface and DTOs — no logic yet.

- Create `pom.xml` with Spring Boot 3.x, `spring-boot-starter-web`,
  `spring-boot-starter-test`, Java 17
- Package root: `com.chegg.ratelimiter`
- Define `RateLimiter` interface:
  ```java
  RateLimitResult checkAndRecord(String clientKey, String endpoint);
  RateLimitStatus getStatus(String clientKey, String endpoint);
  ```
- Create records/DTOs: `RateLimitResult`, `RateLimitStatus`, `ErrorResponse`,
  `RateLimitEndpointConfig`
- Create empty stubs for `RateLimiterServiceTest` and `ApiControllerTest`

**Tests:** Project compiles, `mvn test` runs with 0 failures.  
**Demo:** Clean scaffold builds and test runner executes successfully.

---

### Task 2: SlidingWindowRateLimiterService (RED → GREEN)
**Objective:** Write unit tests T1–T8 first (all fail), then implement the algorithm.

- Write all 8 unit tests in `RateLimiterServiceTest` — RED phase
- Implement `SlidingWindowRateLimiterService implements RateLimiter`:
  - `ConcurrentHashMap<String, List<Long>>` keyed by `"clientKey::endpoint"`
  - Evict timestamps older than `now - windowSizeInSeconds * 1000`
  - Use `Collections.synchronizedList(new ArrayList<>())` for inner list thread safety
  - Inject `java.time.Clock` via constructor for testable time control
  - `resetAt` = oldest timestamp in window + `windowSizeInSeconds`
- Annotate as `@Service`

**Tests:** T1, T2, T3, T4, T5, T6, T7, T8 — all green.  
**Demo:** Isolated algorithm demo via unit tests — correct allow/block at all boundaries.

---

### Task 3: RateLimitConfig — Per-Endpoint Configuration
**Objective:** Externalize limits to `application.properties` via `@ConfigurationProperties`.

- Create `RateLimitConfig` with `@ConfigurationProperties(prefix = "rate-limit")`
- `application.properties`:
  ```properties
  rate-limit.endpoints[/api/general].limit=20
  rate-limit.endpoints[/api/general].window-size-in-seconds=60
  rate-limit.endpoints[/api/submit].limit=5
  rate-limit.endpoints[/api/submit].window-size-in-seconds=60
  rate-limit.endpoints[/api/status].limit=60
  rate-limit.endpoints[/api/status].window-size-in-seconds=60
  ```
- Inject config map into `SlidingWindowRateLimiterService`

**Tests:** Config-loading test — assert correct limit resolved per path.  
**Demo:** Change a limit in properties, restart — new limit applies without code change.

---

### Task 4: RateLimitInterceptor + WebConfig (RED → GREEN)
**Objective:** Write interceptor integration test stubs first (fail), then implement.

- Write `ApiControllerTest` stubs for T9, T13, T15 — RED phase
- Create `RateLimitInterceptor implements HandlerInterceptor`:
  - `preHandle()`: extract `X-API-Key` → return 400 if absent
  - Call `rateLimiter.checkAndRecord(apiKey, requestURI)`
  - Always write `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`
  - On blocked: write 429 JSON body, set `Retry-After`, return `false`
  - On allowed: return `true`
- Create `WebConfig implements WebMvcConfigurer`:
  - Register interceptor for `/api/**` via `addInterceptors()`

**Tests:** T9 (headers on 200), T13 (missing key → 400), T15 (Retry-After on 429).  
**Demo:** `curl -H "X-API-Key: test" localhost:8080/api/general` → 200 with all rate limit headers.

---

### Task 5: ApiController — Demo Endpoints
**Objective:** Implement the three gated endpoints, wire remaining integration tests green.

- `GET /api/general` → `{"message": "OK"}`
- `POST /api/submit` → `{"message": "OK"}`
- `GET /api/status`:
  - Extract `X-API-Key` from request
  - Call `rateLimiter.getStatus(apiKey, "/api/status")`
  - Return `{"limit": N, "remaining": N, "resetAt": N}`

**Tests:** T9, T11, T12, T14 — all green.  
**Demo:** All three endpoints respond correctly. `/api/status` shows live `remaining` count decreasing per call.

---

### Task 6: Scheduled Memory Cleanup
**Objective:** Prevent unbounded memory growth from accumulating timestamp logs.

- Add `@EnableScheduling` to `RateLimiterApplication`
- Add `@Scheduled(fixedDelay = 60000)` method `cleanupExpiredEntries()` in service:
  - Iterate map entries
  - Evict old timestamps from each list
  - Remove entry entirely if list is empty after eviction
- Use injected `Clock` so cleanup logic is testable without `Thread.sleep`

**Tests:** Populate map → advance clock → trigger cleanup → assert map is empty.  
**Demo:** Under load, map size stays bounded. Cleanup log output visible every 60s.

---

### Task 7: Full Edge-Case Test Suite
**Objective:** Complete T10 and T16, verify all 16 tests pass end-to-end.

- T10: Fire 21 requests to `/api/general` — assert 20th is 200, 21st is 429 with correct body
- T16: Use `@TestPropertySource` to set 1-second window, inject a `Clock` that
  advances past the window, assert next request is allowed
- Verify `X-RateLimit-Reset` is always in the future on allowed responses
- Verify `alice` and `bob` API keys have fully independent counters across all endpoints

**Tests:** T10, T16 + full regression — all 16 tests green.  
**Demo:** `mvn test` output shows 16/16 passing.

---

### Task 8: README + AI Usage Documentation
**Objective:** Complete the required README covering all spec sections.

**Sections:**
1. **Setup & Run** — `mvn spring-boot:run`, `mvn test`, curl examples
2. **Interceptor vs Filter** — lifecycle difference, when to prefer each
3. **Algorithm choice** — Sliding Window Log pros/cons vs Fixed Window/Token Bucket
4. **Memory cleanup strategy** — why `@Scheduled` over lazy eviction
5. **Distributed extension** — Replace `ConcurrentHashMap` with Redis + Lua atomic
   scripts; shared Redis cluster across pods; `EXPIRE` handles window resets natively
6. **AI Usage (Kiro)**:
   - Which tools: Kiro AI (Planning Agent + Implementation Agent)
   - Example prompts used during planning
   - Where AI helped most: scaffold generation, test structure, boilerplate DTOs
   - What was manually verified: algorithm correctness, thread-safety edge cases,
     boundary behavior (Nth vs N+1th request)
   - How correctness was validated: TDD (tests written before implementation),
     MockMvc integration tests, concurrent thread test (T8)

**Demo:** README renders on GitHub. All spec architecture questions answered.

---

## Technical Requirements Checklist

- [ ] Java 17+
- [ ] Spring Boot 3.x
- [ ] Spring Web (no JPA)
- [ ] `ConcurrentHashMap` for in-memory state
- [ ] Maven build tool
- [ ] JUnit 5 + MockMvc
- [ ] No Redis / external store
- [ ] No external rate-limiting libraries (`bucket4j`, `resilience4j`, `guava` etc.)
- [ ] No Spring Security
- [ ] No distributed rate limiting
- [ ] No frontend
- [ ] All 16 tests pass with `mvn test`
- [ ] Service starts with `mvn spring-boot:run`

---

## AI Assistance Log

| Stage | AI Contribution | Human Verification |
|---|---|---|
| Requirements analysis | Parsed spec PDF, extracted all functional/technical requirements | Confirmed all spec points captured |
| Design decisions | Presented pros/cons for all 3 algorithm options and 2 Spring mechanisms | Human selected recommendations |
| Architecture | Generated SOLID mapping, mermaid diagram, project structure | Reviewed for correctness |
| Test case design | Derived 16 test cases from spec requirements | Verified boundary cases (T2, T3, T8, T16) |
| Task ordering | Ordered tasks by TDD dependency (interface → service → config → interceptor → controller) | Confirmed incremental build order |
| README outline | Structured all required sections per spec | To be verified post-implementation |

---



