# Rate Limiter — Spring Boot (Sliding Window Log)

A production-quality, from-scratch rate limiter built with Java 17 and Spring Boot 3.
No external rate-limiting libraries. Pure `ConcurrentHashMap` in-memory state, TDD, and SOLID principles throughout.

---

## Setup & Run

**Prerequisites:** Java 17, Maven 3.x

### Run the server

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # macOS only — skip if Java 17 is default
mvn spring-boot:run
```

Server starts on `http://localhost:8080`.

### Demo UI

Open `http://localhost:8080/` in a browser. Three buttons are displayed:

| Button           | Action                                               |
| ---------------- | ---------------------------------------------------- |
| **GET /general** | Calls `GET /api/general` — 20 req / 60 s limit       |
| **POST /submit** | Calls `POST /api/submit` — 5 req / 60 s limit        |
| **GET /status**  | Calls `GET /api/status` — shows current window state |

Every button click fires a `fetch` request using the default API key `demo-user`. The JSON response is displayed below the buttons, along with live rate-limit header badges (`X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`). When remaining hits 0 the badge turns amber. A 429 response is shown in red with the `retryAfterSeconds` field from the JSON body.

The page itself (`GET /`) is served by `UiController` and is **not** covered by the rate-limit interceptor (interceptor path pattern is `/api/**`).

### Run all tests

```bash
mvn test
```

Expected output: **23 tests, 0 failures, 0 errors** (10 unit + 8 integration + 5 UI).

### curl examples

```bash
# GET /api/general — allowed (returns 200 + rate-limit headers)
curl -i -H "X-API-Key: alice" http://localhost:8080/api/general

# POST /api/submit — 5 req/60 s limit
curl -i -X POST -H "X-API-Key: alice" http://localhost:8080/api/submit

# GET /api/status — see alice's current window state
curl -i -H "X-API-Key: alice" http://localhost:8080/api/status

# Missing key → 400
curl -i http://localhost:8080/api/general

# Exhaust the submit limit and observe 429 + Retry-After
for i in $(seq 1 6); do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST -H "X-API-Key: bob" http://localhost:8080/api/submit
done
# Output: 200 200 200 200 200 429
```

### Response headers (every allowed response)

| Header                  | Description                              |
| ----------------------- | ---------------------------------------- |
| `X-RateLimit-Limit`     | Configured max requests per window       |
| `X-RateLimit-Remaining` | Requests left in the current window      |
| `X-RateLimit-Reset`     | Unix epoch second when the window resets |
| `Retry-After`           | Seconds until retry (429 responses only) |

---

## API Endpoints

| Endpoint       | Method | Limit         |
| -------------- | ------ | ------------- |
| `/api/general` | GET    | 20 req / 60 s |
| `/api/submit`  | POST   | 5 req / 60 s  |
| `/api/status`  | GET    | 60 req / 60 s |

Limits are configured in `src/main/resources/application.properties` — no code change needed to add or adjust endpoints (Open/Closed Principle).

---

## Interceptor vs Filter

Spring provides two extension points for cross-cutting HTTP concerns: `Filter` (Servlet API) and `HandlerInterceptor` (Spring MVC).

**Filter** sits at the Servlet container level, before Spring's `DispatcherServlet`. It sees the raw `HttpServletRequest` / `HttpServletResponse` and has no knowledge of which Spring handler (controller method) will eventually serve the request. Filters are the right choice for concerns that must run regardless of the Spring context — CORS, compression, security.

**HandlerInterceptor** runs inside the `DispatcherServlet`, after routing is resolved. By the time `preHandle()` fires, Spring has already matched the request to a specific controller method and populated the `handler` argument. This gives the interceptor access to handler metadata (e.g. method annotations) and makes path-pattern registration via `WebMvcConfigurer.addInterceptors()` straightforward.

This project uses `HandlerInterceptor` because:

- Rate limiting is an MVC-layer concern: it guards specific mapped endpoints (`/api/**`), not raw servlet paths.
- Path-pattern configuration via `addInterceptors().addPathPatterns()` is cleaner and more idiomatic than filter URL patterns.
- The `handler` argument is available if per-method annotation-driven limits are ever needed (Open/Closed).

---

## Algorithm Choice — Sliding Window Log

Three mainstream algorithms were evaluated:

|                   | Fixed Window Counter    | Sliding Window Log        | Token Bucket           |
| ----------------- | ----------------------- | ------------------------- | ---------------------- |
| **Accuracy**      | Low — burst at boundary | High                      | Medium-High            |
| **Memory**        | One counter per client  | One timestamp per request | One bucket per client  |
| **Complexity**    | Trivial                 | Medium                    | Medium                 |
| **Main weakness** | 2× burst at window edge | Memory grows with traffic | Allows burst by design |

**Why Sliding Window Log:**

Fixed Window is ruled out immediately: a client can fire `2 × limit` requests in a short span by timing requests at the boundary of two windows. The spec calls for accurate per-client, per-endpoint enforcement — Fixed Window can't guarantee that.

Token Bucket allows deliberate bursting (that's a feature for some use-cases, not here). It also needs careful parameter tuning (refill rate, bucket capacity) and is harder to reason about for a hard "N requests per window" contract.

Sliding Window Log gives an exact count of requests in any rolling window of `windowSizeInSeconds`. Every request stores exactly one `Long` timestamp; eviction removes entries older than `now - windowMs` on every call. Memory is proportional to request volume, but the scheduled cleanup (see below) keeps it bounded in practice.

---

## Memory Cleanup Strategy — `@Scheduled` vs Lazy Eviction

Two common patterns for cleaning up stale rate-limit data:

**Lazy eviction** — evict on every `checkAndRecord` call. This is what the service already does per-list (timestamps older than the window are removed before counting). It keeps individual lists clean, but entries for clients that have gone quiet are never removed from the outer `ConcurrentHashMap`. Over time, an endpoint that sees millions of distinct API keys accumulates millions of empty-but-present map entries.

**`@Scheduled` cleanup** — a background task runs every 60 seconds, iterates all map entries, evicts expired timestamps, and removes entries whose lists are empty after eviction.

This project uses `@Scheduled` (with lazy eviction still in place on the hot path) because:

- It does not add latency to individual requests — the hot-path eviction is O(expired timestamps), not O(all clients).
- It guarantees that quiet-client entries are eventually reclaimed, keeping memory O(active clients × request rate) rather than O(all-time distinct clients).
- The scheduled task uses the injected `Clock`, so cleanup logic is fully testable without `Thread.sleep`.

---

## Distributed Extension

The current implementation is intentionally single-process (spec requirement). Extending to a distributed environment requires replacing the `ConcurrentHashMap` with a shared atomic store.

**Recommended approach: Redis + Lua scripts**

Replace `SlidingWindowRateLimiterService` with a `RedisRateLimiterService implements RateLimiter` (Liskov Substitution — the interceptor and controller change nothing):

```
KEYS[1]  = "rl::{clientKey}::{endpoint}"
ARGV[1]  = current timestamp (ms)
ARGV[2]  = window size (ms)
ARGV[3]  = limit

-- Lua script (atomic, runs as a single Redis command)
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1] - ARGV[2])   -- evict expired
local count = redis.call('ZCARD', KEYS[1])                            -- count remaining
if count < tonumber(ARGV[3]) then
    redis.call('ZADD', KEYS[1], ARGV[1], ARGV[1])                    -- add timestamp
    redis.call('PEXPIRE', KEYS[1], ARGV[2])                          -- auto-expire key
    return 1   -- allowed
else
    return 0   -- blocked
end
```

Key design points:

- A Redis sorted set replaces the in-memory `List<Long>`. The score is the timestamp; `ZREMRANGEBYSCORE` is the eviction step.
- The entire check-and-record is a single Lua script, so it is atomic across all Redis clients — no race conditions.
- `PEXPIRE` on the key means Redis handles its own memory cleanup; no scheduled cleanup task needed.
- All pods share the same Redis cluster, giving consistent rate limits across horizontal scale.
- `@Scheduled` cleanup is no longer needed (Redis TTL handles it natively).

The `RateLimiter` interface remains unchanged — the swap is a single `@Service` class replacement plus Redis connection configuration.

---

## AI Assistance Log (Kiro)

| Stage                 | AI Contribution                                                                           | Human Verification                                         |
| --------------------- | ----------------------------------------------------------------------------------------- | ---------------------------------------------------------- |
| Requirements analysis | Parsed spec, extracted all functional and technical requirements                          | Confirmed all spec points captured                         |
| Design decisions      | Presented pros/cons for all three algorithm options and two Spring mechanisms             | Human selected recommended options                         |
| Architecture          | Generated SOLID mapping, Mermaid diagram, project structure                               | Reviewed for correctness                                   |
| Test case design      | Derived 16 test cases from spec requirements                                              | Verified boundary cases (T2, T3, T8, T16)                  |
| Task ordering         | Ordered tasks by TDD dependency (interface → service → config → interceptor → controller) | Confirmed incremental build order                          |
| Implementation        | Generated all source files and test files following the plan                              | Reviewed algorithm logic, thread-safety, eviction boundary |
| Bug fix               | Identified T7 test logic error (window boundary off-by-one) and corrected it              | Confirmed fix was mathematically correct                   |
| README                | Authored all required sections                                                            | Reviewed for accuracy                                      |

**Tools used:** Kiro AI (Planning + Implementation agent, Autopilot mode)

**Where AI helped most:**

- Scaffold generation — `pom.xml`, package structure, all boilerplate DTOs and records produced in one pass.
- Test structure — all 18 test cases (names, assertions, helper classes) derived directly from the spec table.
- Clock injection pattern — `MutableClock extends Clock` approach for testable time control without `Thread.sleep`.

**What was manually verified:**

- Sliding window eviction boundary: the cutoff is `ts <= nowMs - windowMs` (exclusive lower bound), not `< nowMs - windowMs`. Verified that the `LIMIT`-th request passes and the `(LIMIT+1)`-th is blocked (T2, T3).
- Thread-safety: `synchronized (timestamps)` block wraps both eviction and insertion atomically; `ConcurrentHashMap.computeIfAbsent` handles concurrent first-request races.
- T7 test logic: initial version used `WINDOW_SECS - 5` advance, which keeps original timestamps inside the window. Corrected to `WINDOW_SECS + 1` so they fall out.
- T16 Clock injection: confirmed the test `@TestConfiguration` exposes a single `MutableClock` bean that satisfies both `Clock` and `MutableClock` injection points, avoiding a duplicate-primary ambiguity in Spring Boot 3.

**How correctness was validated:**

- TDD — all tests written before or alongside implementation; red → green cycle confirmed.
- `mvn test` — 18/18 tests green, 0 failures, 0 errors.
- Concurrent thread test (T8) — 100 threads fired simultaneously; exactly `LIMIT` allowed, rest blocked.

---

## Project Structure

```
src/
├── main/
│   ├── java/com/chegg/ratelimiter/
│   │   ├── RateLimiterApplication.java          # @SpringBootApplication + @EnableScheduling
│   │   ├── controller/
│   │   │   ├── ApiController.java               # GET /api/general, POST /api/submit, GET /api/status
│   │   │   └── UiController.java                # GET / → serves index.html (no rate-limit logic)
│   │   ├── interceptor/
│   │   │   └── RateLimitInterceptor.java        # preHandle: key check, allow/deny, headers
│   │   ├── service/
│   │   │   ├── RateLimiter.java                 # interface — checkAndRecord + getStatus
│   │   │   └── SlidingWindowRateLimiterService  # algorithm + @Scheduled cleanup
│   │   ├── config/
│   │   │   ├── ClockConfig.java                 # Clock bean (overridable in tests)
│   │   │   ├── WebConfig.java                   # addInterceptors for /api/**
│   │   │   └── RateLimitConfig.java             # @ConfigurationProperties per-endpoint limits
│   │   └── dto/
│   │       ├── RateLimitResult.java             # allowed, limit, remaining, resetAt, retryAfter
│   │       ├── RateLimitStatus.java             # limit, remaining, resetAt (read-only)
│   │       ├── StatusResponse.java              # /api/status JSON body
│   │       ├── ErrorResponse.java               # 429 JSON body
│   │       └── RateLimitEndpointConfig.java     # per-endpoint limit + windowSizeInSeconds
│   └── resources/
│       ├── application.properties
│       └── templates/
│           └── index.html                       # UI page — 3 buttons, JS fetch, header badges
└── test/
    ├── java/com/chegg/ratelimiter/
    │   ├── service/
    │   │   └── RateLimiterServiceTest.java      # T1–T8 + cleanup + getStatus (10 tests)
    │   └── controller/
    │       ├── ApiControllerTest.java           # T9–T16 MockMvc integration (8 tests)
    │       └── UiControllerTest.java            # T17–T21 UI page + button endpoints (5 tests)
    └── resources/
        └── application-test.properties         # allow-bean-definition-overriding=true
```

---

## Technical Requirements

- [x] Java 17
- [x] Spring Boot 3.x
- [x] Spring Web (no JPA)
- [x] Thymeleaf (UI template engine)
- [x] `ConcurrentHashMap` for in-memory state
- [x] Maven build tool
- [x] JUnit 5 + MockMvc
- [x] No Redis / external store
- [x] No external rate-limiting libraries (`bucket4j`, `resilience4j`, `guava`, etc.)
- [x] No Spring Security
- [x] No distributed rate limiting
- [x] UI page at `GET /` with 3 buttons (General, Submit, Status)
- [x] All 23 tests pass with `mvn test`
- [x] Service starts with `mvn spring-boot:run`
