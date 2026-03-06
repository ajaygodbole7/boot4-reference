# SPEC: Targeted Exception Handling Improvements

## Objective

Make targeted improvements to the exception handling layer: close coverage gaps, reduce
test brittleness, and remove only the handlers that genuinely add no value over Spring
Boot 4's built-in behavior. This is a conservative refactor — no architectural upheaval.

### What prompted this

An audit of the current `ExceptionTranslator` (13 handlers, ~313 lines) identified:
- Missing coverage for transient infrastructure failures (DB down, query timeout → 500
  instead of 503)
- Test brittleness risk from asserting on framework-internal title strings (tests pass
  today but are fragile against Spring validation dispatch changes or controller method
  signature changes — e.g., adding a `@Size` constraint to a method parameter)
- Two redundant handlers for lock exceptions (consolidable into one)
- Four validation/parse handlers silently skip logging (observability gap)
- JSON parse handler leaks internal Java type names to clients (information disclosure)
- Fragile detail-override pattern used by 4+ handlers (raw `ex.getMessage()` briefly
  in ProblemDetail before overwrite)
- Dead `crossParameterErrors` branch with no test coverage

### What this is NOT

- Not migrating domain exceptions to `ErrorResponseException` (that would couple
  domain to `org.springframework.web` — pure domain concepts like "insufficient stock"
  should not know about HTTP status codes)
- Not extending `ResponseEntityExceptionHandler` (would disable Spring Boot's
  auto-configured `ProblemDetailsExceptionHandler`, creating a dual error format risk
  per [spring-boot#43850](https://github.com/spring-projects/spring-boot/issues/43850))
- Not removing handlers that produce rich structured error payloads (validation errors,
  type mismatch details, JSON parse details)
- Not removing `@Order(HIGHEST_PRECEDENCE)` (it guarantees we control the entire error
  response format — that's a feature, not a bug)

### Success criteria

- All existing API error contracts preserved exactly (status codes, response structure,
  field-level validation details)
- New coverage: transient infrastructure failures return 503 instead of 500
- New test: `TransientDataAccessException` handler verified in `ExceptionTranslatorTest`
- Lock exception handlers consolidated (2 → 1) with neutral error message
- No `OrderControllerTest` asserts on framework-internal validation title strings (see
  Task 4 for scoping rationale)
- All exception handlers log consistently (no silent 400s)
- JSON parse handler does not leak internal type information in non-dev profiles
- Detail-override pattern eliminated via `buildScrubbedErrorResponse` helper
- Dead `crossParameterErrors` branch removed
- `mvn test` passes with 0 failures

---

## Background

### Why NOT extend `ResponseEntityExceptionHandler`

Investigated via bytecode analysis of Spring Framework 7.0.5. Three deal-breakers:

**1. Loses rich structured error payloads.**
Spring's built-in `handleMethodArgumentNotValid()` passes `null` body to
`handleExceptionInternal()`, which calls `MethodArgumentNotValidException.getBody()`.
That returns `ProblemDetail.forStatusAndDetail(BAD_REQUEST, "Invalid request content.")` —
a bare-bones response with no field errors.

Our current handler builds a structured `errors` array:
```json
{
  "errors": [{
    "field": "name",
    "rejectedValue": "null",
    "message": "must not be blank",
    "errorCode": "NotBlank",
    "bindingFailure": "false"
  }]
}
```

Similarly, `handleMethodValidation` builds `validationErrors` with parameter name, type,
annotation source, and cross-parameter errors. `handleTypeMismatch` returns `parameter`,
`expectedType`, `invalidValue`. `handleJsonParseError` extracts the most specific cause.
All of this would be lost.

**2. Domain coupling anti-pattern.**
`ErrorResponseException` has no `(String)` constructor. Its simplest constructor is
`(HttpStatusCode)`. Making `ResourceNotFoundException` extend it would require:
- Every base exception to hardcode an HTTP status code
- Every leaf exception constructor to change (currently they call `super("message")`)
- Domain packages to depend on `org.springframework.web`

A pure domain concept like `InsufficientStockException` should not know about HTTP 422.

**3. Dual error format risk.**
Extending `ResponseEntityExceptionHandler` disables Spring Boot's auto-configured
`ProblemDetailsExceptionHandler`. Any exception that slips through both the base class's
20-type instanceof cascade AND our custom handlers falls to `BasicErrorController`, which
returns the legacy Spring Boot error format — not RFC 9457.

### Spring Data exception hierarchy (verified via bytecode, Spring TX 7.0.5)

Understanding this hierarchy is critical for handler ordering:

```
DataAccessException
├── NonTransientDataAccessException
│   └── DataIntegrityViolationException          → 409 (own handler)
└── TransientDataAccessException                 → 503 (Task 1 handler)
    ├── TransientDataAccessResourceException     → 503 (via TransientDataAccess)
    ├── QueryTimeoutException                    → 503 (via TransientDataAccess)
    │   NOTE: DataAccessResourceFailureException is NOT shown here — it actually
    │   extends NonTransientDataAccessResourceException (non-transient), not
    │   TransientDataAccessException. The handler and tests correctly use
    │   TransientDataAccessResourceException instead.
    └── ConcurrencyFailureException              → 409 (Task 2 handler, MORE SPECIFIC)
        ├── ObjectOptimisticLockingFailureException  → 409 (via ConcurrencyFailure)
        └── PessimisticLockingFailureException       → 409 (via ConcurrencyFailure)
            └── CannotAcquireLockException            → 409 (via ConcurrencyFailure)
```

Spring's `ExceptionHandlerMethodResolver` uses `ExceptionDepthComparator` to always
select the most-specific handler. For example, an `ObjectOptimisticLockingFailureException`
matches both `ConcurrencyFailureException` (depth 1) and `TransientDataAccessException`
(depth 2) — Spring picks `ConcurrencyFailureException`. **Declaration order of
`@ExceptionHandler` methods does not affect resolution.**

### Why the 405/415/406 handlers CANNOT be removed

Initially considered removing `handleMethodNotSupported` (405),
`handleMediaTypeNotSupported` (415), and `handleMediaTypeNotAcceptable` (406) since
Spring's built-in `ProblemDetailsExceptionHandler` handles them identically.

**However, this is blocked by our `@Order(HIGHEST_PRECEDENCE)` + catch-all design.**

Verified via bytecode: `ExceptionHandlerExceptionResolver.getExceptionHandlerMethod()`
iterates `@ControllerAdvice` beans in `@Order` sequence. For each advice, it calls
`ExceptionHandlerMethodResolver.resolveExceptionMapping()` which uses
`ExceptionDepthComparator` to find the best match **within that single advice**. If any
match is found, it returns immediately (`areturn`) — it never compares matches across
multiple advice beans.

Since `ExceptionTranslator` is at `HIGHEST_PRECEDENCE`, it's checked first. Without
specific 405/415/406 handlers, `@ExceptionHandler(Exception.class)` becomes the best
match for these exceptions. Spring returns that match immediately and never checks
Spring Boot's `ProblemDetailsExceptionHandler`.

**Result: all 405/415/406 errors would return 500 "An unexpected internal error occurred."**

This is an inherent design tension: `@Order(HIGHEST_PRECEDENCE)` + catch-all + "let
Spring handle some exceptions" are mutually incompatible. Since we need both the ordering
guarantee and the catch-all, these 3 handlers must stay.

---

## Tech Stack

- Java 25, Spring Boot 4.0.3, Spring Framework 7.0.5
- Hibernate 7.2.x, Spring Data JPA
- Build/test: `mvn test` (no wrapper script)
- Test: JUnit 5, `@WebMvcTest`, MockMvc, Mockito

---

## Tasks

### Task 1: Add `TransientDataAccessException` handler

**File:** `src/main/java/.../common/rest/ExceptionTranslator.java`

Add a new `@ExceptionHandler` for `TransientDataAccessException` → 503. This is the
common superclass covering:
- `DataAccessResourceFailureException` (DB connection refused/lost)
- `QueryTimeoutException` (query execution timeout)
- `TransientDataAccessResourceException` (transient resource failures)

**Note:** `CannotAcquireLockException` is also a subclass of `TransientDataAccessException`
(via `PessimisticLockingFailureException` → `ConcurrencyFailureException` →
`TransientDataAccessException`), but it will be handled by the more-specific
`ConcurrencyFailureException` handler from Task 2. Spring uses `ExceptionDepthComparator`
to always select the closest matching handler in the class hierarchy — declaration order
of `@ExceptionHandler` methods does not matter.

```java
@ExceptionHandler(TransientDataAccessException.class)
public ResponseEntity<ProblemDetail> handleTransientDataAccess(
        TransientDataAccessException ex, HttpServletRequest request) {
    var response = buildErrorResponse(
            HttpStatus.SERVICE_UNAVAILABLE, "Service Temporarily Unavailable", ex, request);
    Objects.requireNonNull(response.getBody())
            .setDetail("Database temporarily unavailable, please retry");
    return response;
}
```

**Handler resolution:** Spring's `ExceptionHandlerMethodResolver` uses
`ExceptionDepthComparator` to find the most-specific handler. For any
`ConcurrencyFailureException` (or subclass), Spring will always pick the
`ConcurrencyFailureException` → 409 handler (depth 0) over this
`TransientDataAccessException` → 503 handler (depth 1+). Declaration order is irrelevant.

**Import to add:** `org.springframework.dao.TransientDataAccessException`

**Validation:** `mvn test` — all existing tests should still pass (this is purely additive).

### Task 2: Consolidate lock exception handlers

**File:** `src/main/java/.../common/rest/ExceptionTranslator.java`

Replace the two separate handlers:
- `handleOptimisticLock` (ObjectOptimisticLockingFailureException → 409)
- `handlePessimisticLock` (PessimisticLockingFailureException → 409)

With a single handler for their common superclass `ConcurrencyFailureException`:

```java
@ExceptionHandler(ConcurrencyFailureException.class)
public ResponseEntity<ProblemDetail> handleConcurrencyFailure(
        ConcurrencyFailureException ex, HttpServletRequest request) {
    var response = buildErrorResponse(HttpStatus.CONFLICT, "Concurrency Conflict", ex, request);
    Objects.requireNonNull(response.getBody())
            .setDetail("Concurrent modification conflict, please retry");
    return response;
}
```

**Import to add:** `org.springframework.dao.ConcurrencyFailureException`

**Imports to remove:**
- `org.springframework.dao.PessimisticLockingFailureException`
- `org.springframework.orm.ObjectOptimisticLockingFailureException`

**Note on the detail message:** The old messages were "Resource was modified by another
request" (optimistic) and "Resource is temporarily locked, please retry" (pessimistic).
These describe different failure modes — optimistic means stale data, pessimistic means
a held lock. The consolidated message uses "Concurrent modification conflict, please retry"
which is accurate for both scenarios without implying a specific failure mode. If clients
programmatically distinguish these, they should use the exception type (visible in
dev-profile responses) rather than the detail string.

**Note on title and errorCode:** Title changes from "Optimistic Lock Conflict" / "Resource
Busy" to "Concurrency Conflict". Since `errorCode` is derived from the title
(`title.toUpperCase().replace(" ", "_")` in `createBaseProblemDetail`), it also changes
from `OPTIMISTIC_LOCK_CONFLICT` / `RESOURCE_BUSY` to `CONCURRENCY_CONFLICT`. No existing
tests assert on `errorCode` for lock conflicts, but any client using this field to
programmatically distinguish lock types will see the new value. Tests asserting on old
titles will need updating (Task 4).

**Validation:** `mvn test` — expect 2 test failures in `ProductControllerTest` for
optimistic lock title/detail assertions. These are fixed in Task 4. **Tasks 2 and 4 should
be implemented as an atomic pair** — apply both before running `mvn test` to avoid a
transient failure state (see "Always do" boundary note).

### Task 3: Add test for `TransientDataAccessException` handler

**File:** `src/test/java/.../common/rest/ExceptionTranslatorTest.java`

The new `TransientDataAccessException` handler (Task 1) must have a test. Without one,
there's no proof it returns 503 instead of falling through to the catch-all 500, and no
regression detection if the handler is accidentally removed.

Add a test endpoint to the embedded `TestController`:

```java
@GetMapping("/test/transient-failure")
public void throwTransientDataAccessException() {
    throw new DataAccessResourceFailureException("Connection refused");
}
```

Add the test method:

```java
@Test
void shouldReturn503WhenTransientDataAccessFailure() throws Exception {
    mockMvc.perform(get("/test/transient-failure"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/503"))
            .andExpect(jsonPath("$.title").value("Service Temporarily Unavailable"))
            .andExpect(jsonPath("$.detail").value("Database temporarily unavailable, please retry"))
            .andExpect(jsonPath("$.errorCode").value("SERVICE_TEMPORARILY_UNAVAILABLE"))
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.request").exists());
}
```

This matches the thoroughness of the peer test `shouldReturn503WhenServiceUnavailable`
(line 125), which also asserts `$.type`, `$.errorCode`, `$.timestamp`, and `$.request`.

Also add a **hierarchy routing test** to prove `ConcurrencyFailureException` → 409 wins
over `TransientDataAccessException` → 503. This is the spec's most critical correctness
assumption and should be verified by a test, not just documentation.

Add a second test endpoint:

```java
@GetMapping("/test/optimistic-lock-failure")
public void throwOptimisticLockException() {
    throw new ObjectOptimisticLockingFailureException("Product", 42L);
}
```

Add the test:

```java
@Test
void shouldReturn409NotS503WhenConcurrencyFailure() throws Exception {
    // ConcurrencyFailureException extends TransientDataAccessException.
    // Verify ExceptionDepthComparator routes to 409, not 503.
    mockMvc.perform(get("/test/optimistic-lock-failure"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.title").value("Concurrency Conflict"))
            .andExpect(jsonPath("$.detail").value("Concurrent modification conflict, please retry"))
            .andExpect(jsonPath("$.status").value(409));
}
```

**Imports to add:**
- `org.springframework.dao.DataAccessResourceFailureException`
- `org.springframework.orm.ObjectOptimisticLockingFailureException`

**Validation:** `mvn test` — both new tests pass, all existing tests still pass.

### ~~Task 3 (original): Remove 3 redundant handlers~~ — DROPPED

Originally planned to remove `handleMethodNotSupported` (405),
`handleMediaTypeNotSupported` (415), and `handleMediaTypeNotAcceptable` (406).

**Dropped because:** `@Order(HIGHEST_PRECEDENCE)` + catch-all `Exception` handler means
removing these would cause all 405/415/406 to return 500. Spring's
`ExceptionHandlerExceptionResolver` stops at the first `@ControllerAdvice` that has
any match — the catch-all matches everything. See "Why the 405/415/406 handlers CANNOT
be removed" in the Background section for full bytecode analysis.

7 tests would fail:
- `ExceptionTranslatorTest`: 405, 415, 406 tests
- `ProductControllerTest`: 415, 405 tests
- `OrderControllerTest`: 415, 405 tests

### Task 4 (was Task 5): Fix brittle test assertions

**Principle:** Tests should assert on **status codes** and **response structure** for
Spring-handled validation errors. Tests may assert on **title strings** only for exceptions
where we control the title (domain exceptions, custom handlers).

**Files to modify:**

**`OrderControllerTest.java`** — validation error tests:
```java
// Replace assertions like:
.andExpect(jsonPath("$.title").value("Method Validation Error"));
// With structural ProblemDetail assertions:
.andExpect(status().isBadRequest())
.andExpect(jsonPath("$.type").exists())
.andExpect(jsonPath("$.detail").exists());
```

This proves the response is RFC 9457-shaped without depending on title strings. Note:
`jsonPath("$.status").value(400)` would be redundant with `status().isBadRequest()` —
they always agree because Spring sets both from the same source. Structural assertions
(`$.type` exists, `$.detail` exists) are more meaningful.

Tests affected (4 validation tests):
- `shouldReturn400WhenOrderHasNoItems`
- `shouldReturn400WhenOrderItemHasNullProductId`
- `shouldReturn400WhenOrderItemHasZeroQuantity`
- `shouldReturn400WhenOrderItemHasNegativeQuantity`

Also update line 295 (`shouldReturn400WhenStatusRequestIsMissingStatus`) to stop asserting
on `"Validation Error"` title — same pattern.

**Why only OrderControllerTest?** `ProductApi` methods have no method-parameter constraints
(no `@Size`, `@Min`, etc. on parameters — only `@Valid @RequestBody`). Their validation
always dispatches through `MethodArgumentNotValidException`, making the title "Validation
Error" stable. The brittleness exists specifically because `OrderApi.createOrder()` has
`@Size(max = 255)` on `idempotencyKey` (line 39) alongside `@Valid @RequestBody`, which
causes Spring to reroute ALL validation on that method through
`HandlerMethodValidationException`. `ExceptionTranslatorTest` uses a dedicated
`TestController` with explicit exception-triggering endpoints — stable by construction.

**`ProductControllerTest.java`** — optimistic lock tests:
```java
// Update title from "Optimistic Lock Conflict" to "Concurrency Conflict"
.andExpect(jsonPath("$.title").value("Concurrency Conflict"))
.andExpect(jsonPath("$.detail").value("Concurrent modification conflict, please retry"));
```

Tests affected (2 optimistic lock tests):
- `shouldReturn409WhenPutHasStaleVersion`
- `shouldReturn409WhenPatchHasStaleVersion`

**`ExceptionTranslatorTest.java`** — no changes needed for 405/415/406 tests (handlers
are being kept, not removed).

**`ExceptionTranslatorDevProfileTest.java`** — no changes needed (tests the catch-all
handler which is unchanged).

**Validation:** `mvn test` — 0 failures.

### Kept handlers (documented decision, no code changes)

The following handlers are explicitly **kept unchanged**. They produce structured error
data that Spring's defaults do not, handle exceptions Spring doesn't cover, or cannot be
removed due to the `@Order(HIGHEST_PRECEDENCE)` + catch-all design:

**Rich structured data handlers (5):**
1. **`handleValidationException`** (MethodArgumentNotValidException → 400)
   — `errors[]` with field, objectName, rejectedValue, message, errorCode, bindingFailure
2. **`handleMethodValidation`** (HandlerMethodValidationException → 400)
   — `validationErrors[]` with parameter, type, messages, errorCode, annotation
3. **`handleTypeMismatch`** (MethodArgumentTypeMismatchException → 400)
   — `parameter`, `expectedType`, `invalidValue`
4. **`handleJsonParseError`** (HttpMessageNotReadableException → 400)
   — Most specific cause message
5. **`handleConstraintViolation`** (jakarta.validation.ConstraintViolationException → 400)
   — Not covered by Spring's `ResponseEntityExceptionHandler`

**Cannot-remove handlers (3)** — would be swallowed by catch-all (see Background):
6. **`handleMethodNotSupported`** (HttpRequestMethodNotSupportedException → 405)
7. **`handleMediaTypeNotSupported`** (HttpMediaTypeNotSupportedException → 415)
8. **`handleMediaTypeNotAcceptable`** (HttpMediaTypeNotAcceptableException → 406)

---

## Code Quality Improvements

These tasks address observability, security, and code hygiene issues found during code
review. They are independent of Tasks 1-4 and can be applied in any order.

### Task 5: Add logging to validation/parse handlers

**File:** `src/main/java/.../common/rest/ExceptionTranslator.java`

Three handlers bypass `buildErrorResponse` and therefore skip the logging block (lines
264-271). Every other error type in the system is logged; these three are invisible —
a real observability gap for the most common client errors.

**Affected handlers (3 — not 4):**
- `handleValidationException` (line 115) — no logging
- `handleMethodValidation` (line 175) — no logging
- `handleTypeMismatch` (line 237) — no logging

**Note:** `handleJsonParseError` (line 138) also lacks logging, but Task 6 rewrites that
handler entirely — including adding the log.warn call. Do not add logging to
`handleJsonParseError` in this task; Task 6 handles it.

Add `log.warn` to each of the 3 handlers, matching the format used by `buildErrorResponse`
for 4xx:

```java
log.warn("{} {} -> 400 {}", request.getMethod(), request.getRequestURI(), "Validation Error");
```

Place the log call before the `return` statement in each handler. Use the handler's own
title string (e.g., "Validation Error", "Method Validation Error", "Invalid Path Variable")
to match the existing logging pattern.

**Validation:** `mvn test` — no test changes needed. Verify via log output in dev that
validation errors now appear in logs.

### Task 6: Scrub internal type info from `handleJsonParseError`

**File:** `src/main/java/.../common/rest/ExceptionTranslator.java`

The current handler (lines 143-146) passes Jackson's `getMostSpecificCause().getMessage()`
directly to the client. Jackson messages regularly contain internal implementation details:
- `"Cannot deserialize value of type 'java.math.BigDecimal' from String \"abc\""`
- `"Unrecognized field \"internalField\" (class com.example.boot4ref.product.rest.ProductCreateRequest)"`

This leaks Java class names and field names to clients. The catch-all handler (line 256-257)
explicitly scrubs messages for this reason, but this handler passes them through raw.

**Fix:** Use a generic detail in non-dev profiles; include the raw detail only in dev:

```java
@ExceptionHandler(HttpMessageNotReadableException.class)
public ResponseEntity<ProblemDetail> handleJsonParseError(
        HttpMessageNotReadableException ex, HttpServletRequest request) {
    ProblemDetail problemDetail =
            createBaseProblemDetail(HttpStatus.BAD_REQUEST, "Malformed JSON", ex, request);
    String rawDetail = Optional.ofNullable(ex.getMostSpecificCause())
            .map(cause -> "JSON parsing error: " + cause.getMessage())
            .orElse("Malformed JSON input: " + ex.getMessage());
    problemDetail.setDetail("Malformed JSON request body");
    if (isDevProfile) {
        problemDetail.setProperty("parseError", rawDetail);
    }
    log.warn("{} {} -> 400 Malformed JSON", request.getMethod(), request.getRequestURI());
    return ResponseEntity.badRequest().body(problemDetail);
}
```

**Note:** The generic detail "Malformed JSON request body" tells the client what went wrong
without revealing internals. The raw Jackson message is available in dev-profile responses
under `$.parseError` for debugging. This handler also includes the logging fix (Task 5
does not need to add logging to this handler — Task 6 supersedes it).

**Client-visible API change:** This changes the `$.detail` field for JSON parse errors from
the raw Jackson message (e.g., `"JSON parsing error: Cannot deserialize value of type
'java.math.BigDecimal'"`) to the generic string `"Malformed JSON request body"`. This is a
deliberate breaking change for security reasons (information disclosure). Clients that parse
the detail string for error specifics will need to adapt. The raw detail is still available
in dev-profile responses via `$.parseError`.

**Test updates:**

`ExceptionTranslatorTest.shouldReturn400WhenMalformedJson` (line 222) — update to assert
the new generic detail:

```java
.andExpect(jsonPath("$.detail").value("Malformed JSON request body"))
```

`ExceptionTranslatorDevProfileTest` — add a new test to verify the raw Jackson message
is available in dev-profile via `$.parseError`:

```java
@Test
void shouldIncludeParseErrorInDevProfileForMalformedJson() throws Exception {
    mockMvc.perform(post("/test/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{bad json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Malformed JSON request body"))
            .andExpect(jsonPath("$.parseError").exists());
}
```

**Imports to add to `ExceptionTranslatorDevProfileTest`:**
- `org.springframework.http.MediaType`
- `static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post`

**Validation:** `mvn test` — 1 test assertion update + 1 new dev-profile test.

### Task 7: Introduce `buildScrubbedErrorResponse` helper

**File:** `src/main/java/.../common/rest/ExceptionTranslator.java`

**Dependency:** Must be applied after Tasks 1 and 2 (it refactors handlers they create).

Four handlers follow the fragile pattern of calling `buildErrorResponse` (which sets
`ex.getMessage()` as the detail) and then immediately overwriting the detail. The raw
infrastructure message sits in the `ProblemDetail` object during the window between
construction and override. Callers can forget to override, silently leaking raw messages.

**Current pattern (4 handlers):**
- `handleConcurrencyFailure` (Task 2) — overwrites with "Concurrent modification conflict..."
- `handleDataIntegrityViolation` (line 98) — overwrites with "Operation violates..."
- `handleTransientDataAccess` (Task 1) — overwrites with "Database temporarily unavailable..."
- `handleUnexpectedException` (line 250) — overwrites with "An unexpected internal error..."

**Extract a new helper** that delegates to `buildErrorResponse` and immediately overwrites
the detail. This reuses the existing logging and ProblemDetail construction — no code
duplication:

```java
private ResponseEntity<ProblemDetail> buildScrubbedErrorResponse(
        HttpStatus status, String title, String safeDetail,
        Exception ex, HttpServletRequest request) {
    var response = buildErrorResponse(status, title, ex, request);
    Objects.requireNonNull(response.getBody()).setDetail(safeDetail);
    return response;
}
```

The raw `ex.getMessage()` is still briefly set by `buildErrorResponse` and immediately
overwritten — but this window is now encapsulated within a single method rather than
exposed to each caller. The key improvement is that callers cannot forget to override.

**Update callers** — each becomes a single line:

```java
// handleConcurrencyFailure:
return buildScrubbedErrorResponse(HttpStatus.CONFLICT, "Concurrency Conflict",
        "Concurrent modification conflict, please retry", ex, request);

// handleDataIntegrityViolation:
return buildScrubbedErrorResponse(HttpStatus.CONFLICT, "Data Integrity Violation",
        "Operation violates a data integrity constraint (e.g. referenced by other records)", ex, request);

// handleTransientDataAccess:
return buildScrubbedErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Service Temporarily Unavailable",
        "Database temporarily unavailable, please retry", ex, request);

// handleUnexpectedException:
log.error("Unexpected error occurred", ex);
return buildScrubbedErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
        "An unexpected internal error occurred", ex, request);
```

**Behavior change: catch-all now logs twice.** Currently the catch-all only has
`log.error("Unexpected error occurred", ex)`. After this change, `buildScrubbedErrorResponse`
delegates to `buildErrorResponse` which adds a second log line:
`log.error("GET /api/foo -> 500 Internal Server Error")`. These serve different purposes —
the first is the diagnostic log (what went wrong, with stack trace), the second is the
consistent request-level log (what the client got). This is intentional but is NOT a
pure refactor — it adds a log line to 500 responses.

**Validation:** `mvn test` — all existing tests pass. Verify catch-all 500 responses
now produce two log lines in dev.

### Task 8: Remove dead `crossParameterErrors` branch

**File:** `src/main/java/.../common/rest/ExceptionTranslator.java`

Lines 226-232 handle `crossParameterValidationResults`:

```java
if (!ex.getCrossParameterValidationResults().isEmpty()) {
    List<String> crossErrors =
            ex.getCrossParameterValidationResults().stream()
                    .map(MessageSourceResolvable::getDefaultMessage)
                    .toList();
    problemDetail.setProperty("crossParameterErrors", crossErrors);
}
```

No cross-parameter constraints exist anywhere in the codebase (confirmed: zero matches
for `@ScriptAssert`, `CrossParameterConstraint`, or any custom cross-parameter validator).
No test exercises this branch. Dead code that was never tested is a liability.

**Delete lines 226-232.** If cross-parameter constraints are added in the future, the
handler can be updated then.

**Validation:** `mvn test` — no test references `crossParameterErrors`. All tests pass.

---

## Files summary

### Modified files

| File | Task | Nature of change |
|------|------|------------------|
| `src/main/java/.../common/rest/ExceptionTranslator.java` | 1, 2, 5, 6, 7, 8 | Add transient handler, consolidate lock handlers, add logging, scrub JSON parse detail, extract `buildScrubbedErrorResponse`, remove dead branch |
| `src/test/java/.../common/rest/ExceptionTranslatorTest.java` | 3, 6 | Add transient failure + hierarchy routing tests, update JSON parse detail assertion |
| `src/test/java/.../order/rest/OrderControllerTest.java` | 4 | Stop asserting validation title strings |
| `src/test/java/.../product/rest/ProductControllerTest.java` | 4 | Update lock conflict title/detail |

### Files NOT modified

- All domain exception classes (no changes to hierarchy or constructors)
- `OrderController.java`, `ProductController.java`
- `OrderService.java`, `ProductService.java`
- `ApplicationProperties.java`, `application.properties`
- `ExceptionTranslatorDevProfileTest.java`
- Any repository, outbox, or Kafka code

---

## Boundaries

### Always do
- Run `mvn test` after each task before proceeding. Exception: Tasks 2 and 4 are an
  atomic pair (Task 2 changes handler titles; Task 4 updates test assertions). Apply both
  before running `mvn test`. Tasks 5-8 are independent of each other and of Tasks 1-4;
  run `mvn test` after each.
- Preserve all HTTP status code mappings
- Preserve structured error payloads (validation `errors[]`, `validationErrors[]`,
  type mismatch details, JSON parse details)
- Keep `@Order(HIGHEST_PRECEDENCE)` on `ExceptionTranslator`
- Keep domain exceptions extending plain `RuntimeException`

### Ask first
- Before modifying any file not listed in the files summary
- Before changing any domain exception's message format
- Before removing a handler that produces structured error data

### Never do
- Do not extend `ResponseEntityExceptionHandler`
- Do not make domain exceptions extend `ErrorResponseException`
- Do not remove `@Order(HIGHEST_PRECEDENCE)`
- Do not remove handlers for MethodArgumentNotValid, HandlerMethodValidation,
  TypeMismatch, HttpMessageNotReadable, or ConstraintViolation
- Do not remove 405/415/406 handlers (catch-all would swallow them — see Background)
- Do not modify `application.properties`
- Do not touch outbox, Kafka, or repository code

---

## Conformance checklist

After all tasks are complete, verify:

- [ ] `ExceptionTranslator` still has `@Order(HIGHEST_PRECEDENCE)`
- [ ] `ExceptionTranslator` does NOT extend `ResponseEntityExceptionHandler`
- [ ] New `TransientDataAccessException` handler returns 503
- [ ] New test verifies `TransientDataAccessException` → 503
- [ ] Single `ConcurrencyFailureException` handler replaces both lock handlers
- [ ] Detail message is neutral: "Concurrent modification conflict, please retry"
- [ ] 405/415/406 handlers preserved (cannot remove due to catch-all)
- [ ] 5 rich/custom handlers preserved (validation, method validation, type mismatch, JSON parse, constraint violation)
- [ ] All domain exceptions still extend `RuntimeException` (not `ErrorResponseException`)
- [ ] `mvn test` passes with 0 failures
- [ ] No `OrderControllerTest` asserts on "Validation Error" or "Method Validation Error" title strings (ProductControllerTest and ExceptionTranslatorTest are exempt — see Task 4 scoping rationale)
- [ ] Structured `errors[]` array still present in validation error responses
- [ ] `validationErrors[]` still present in method validation error responses
- [ ] `parameter`/`expectedType`/`invalidValue` still present in type mismatch responses
- [ ] Dev-profile test still passes (exception class + stack trace in response)
- [ ] `DataIntegrityViolationException` → 409 (unchanged)
- [ ] Catch-all `Exception` → 500 with "An unexpected internal error occurred" (unchanged)
- [ ] Hierarchy routing test proves `ObjectOptimisticLockingFailureException` → 409 (not 503)
- [ ] All 4 validation/parse handlers log at `warn` level
- [ ] `handleJsonParseError` returns generic detail ("Malformed JSON request body") in non-dev
- [ ] `handleJsonParseError` includes raw Jackson message in `$.parseError` in dev-profile
- [ ] `buildScrubbedErrorResponse` used by ConcurrencyFailure, DataIntegrity, TransientDataAccess, catch-all handlers
- [ ] No `crossParameterErrors` branch in `handleMethodValidation`

---

## Decisions log

| Decision | Rationale |
|----------|-----------|
| Keep `@Order(HIGHEST_PRECEDENCE)` | Guarantees we control the entire error format. Removing it shares control with Spring's internal dispatch which has version-sensitive behavior. |
| Keep domain exceptions as `RuntimeException` | `ErrorResponseException` has no `(String)` constructor. Extending it would require every leaf exception to change and would couple domain packages to `org.springframework.web`. |
| Do not extend `ResponseEntityExceptionHandler` | Would lose 4 rich structured error handlers. Would disable Spring Boot's auto-configured `ProblemDetailsExceptionHandler`, creating dual error format risk. |
| Keep 5 rich/custom handlers | 4 produce structured client-facing data (field errors, parameter details) that Spring's defaults do not. 1 (`ConstraintViolationException`) handles an exception type Spring's `ResponseEntityExceptionHandler` doesn't cover at all. Removing them is a silent API contract regression. |
| Keep 405/415/406 handlers | Cannot remove: `@Order(HIGHEST_PRECEDENCE)` + catch-all `Exception` handler means the catch-all would match these first. `ExceptionHandlerExceptionResolver` stops at the first `@ControllerAdvice` with any match — Spring's `ProblemDetailsExceptionHandler` is never reached. Verified via bytecode. |
| Use `ConcurrencyFailureException` as common handler | Common superclass of `ObjectOptimisticLockingFailureException` and `PessimisticLockingFailureException`. Both produce the same 409 response. Uses neutral detail message ("Concurrent modification conflict") accurate for both optimistic (stale data) and pessimistic (held lock) scenarios. |
| Add `TransientDataAccessException` → 503 | Covers DB down, query timeout, transient resource failures. `ConcurrencyFailureException` (also a subclass of `TransientDataAccessException`) is handled by its own more-specific → 409 handler via Spring's `ExceptionDepthComparator`. Currently these fall to catch-all 500, but they are retryable and should signal 503 to clients. |
| Fix test assertions, not architecture | The "Validation Error" vs "Method Validation Error" brittleness is a test problem, not an architecture problem. Fix: assert on status codes, not framework-internal title strings. |
| Introduce `buildScrubbedErrorResponse` | Eliminates the fragile pattern where `buildErrorResponse` temporarily sets `ex.getMessage()` as detail before the caller overwrites it. 4 handlers use this pattern; extracting a helper that takes a `safeDetail` parameter removes the intermediate window entirely. |
| Add logging to validation/parse handlers | 4 handlers (`handleValidationException`, `handleMethodValidation`, `handleTypeMismatch`, `handleJsonParseError`) bypass `buildErrorResponse` to build custom response structures, silently skipping the logging block. 400s are the most common client errors — having zero log trail for them is an observability gap. |
| Scrub JSON parse error details | Jackson's `getMostSpecificCause().getMessage()` contains internal Java type names and field names (e.g., `"Cannot deserialize value of type 'java.math.BigDecimal'"`). This is an information disclosure risk. Generic detail in non-dev; raw detail in dev-profile `$.parseError` for debugging. |
| Remove `crossParameterErrors` branch | Zero cross-parameter constraints in the codebase. No test exercises this branch. Dead code that was never tested is a liability. Can be re-added if cross-parameter constraints are introduced. |
| Defer structured `ConstraintViolation` data extraction | `handleConstraintViolation` discards structured data from `ConstraintViolationException.getConstraintViolations()` (property paths, messages, invalid values). However, `jakarta.validation.ConstraintViolationException` is not thrown by any active controller or service path — the only `@Validated` usage is on `ApplicationProperties` (startup config validation). Medium effort, zero current impact. Deferred to follow-up. |
| `TransactionSystemException` not handled (known gap) | Same underlying failure (DB down) can surface as `DataAccessResourceFailureException` (during query → now 503) or `TransactionSystemException` wrapping `CannotCreateTransactionException` (during tx begin → still 500). Not all `TransactionSystemException`s are transient — commit failures can be permanent. Adding a handler would require distinguishing transient vs permanent causes. Deferred to a follow-up; this spec scopes to the `DataAccessException` hierarchy only. |
| No `Retry-After` header on 503 (future enhancement) | RFC 9110 §15.6.4 recommends `Retry-After` for 503 responses. Both the existing `ServiceUnavailableException` handler and the new `TransientDataAccessException` handler omit it. Adding it to one without the other creates inconsistency. Best addressed holistically in a follow-up across all 503 paths. |
| `instance` URI not unique per occurrence (future enhancement) | RFC 9457 §3.1.1 says `instance` should identify the *specific occurrence*. Currently uses `request.getRequestURI()`, so all errors on `/api/orders/42` share the same `instance`. Appending `X-Request-Id` would make it unique. However, this changes the API contract for every error response and `X-Request-Id` is optional. Best addressed as its own cross-cutting spec. |
