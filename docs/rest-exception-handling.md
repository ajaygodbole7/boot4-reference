# REST Exception Handling - Analysis & Strategy

> **SUPERSEDED (2026-03-07):** This analysis was written pre-RFC 9457 refactor. The tasks in the
> "Final decision" section (Tasks 1–8) are all implemented. The current ExceptionTranslator has
> 16 handlers (~426 lines), uses `@ProblemType` annotations and `ProblemPropertySource` for
> structured error responses, and includes slug-based type URIs, OTel traceId correlation, and
> `Retry-After` headers. See `ExceptionTranslator.java` for the current implementation.

## Project Context
- Spring Boot 4.0.3, Spring Framework 7.0.5, Hibernate 7.2.x
- `spring.mvc.problemdetails.enabled=true` is set in application.properties
- Current `ExceptionTranslator` is a standalone `@RestControllerAdvice` with `@Order(HIGHEST_PRECEDENCE)`
- Does NOT extend `ResponseEntityExceptionHandler`

## Current ExceptionTranslator: 13 Handlers (~313 lines)

### 7 handlers that duplicate Spring Boot 4 built-in **status codes** (but 4 add rich structured data; 3 can't be removed due to catch-all — see decisions below)
These status codes are handled automatically by `ResponseEntityExceptionHandler` when `problemdetails.enabled=true`:
1. `MethodArgumentNotValidException` -> 400 "Validation Error"
2. `HandlerMethodValidationException` -> 400 "Method Validation Error"
3. `HttpMessageNotReadableException` -> 400 "Malformed JSON"
4. `HttpRequestMethodNotSupportedException` -> 405
5. `HttpMediaTypeNotSupportedException` -> 415
6. `HttpMediaTypeNotAcceptableException` -> 406
7. `MethodArgumentTypeMismatchException` -> 400

### 4 domain exception handlers (genuinely custom)
1. `ResourceNotFoundException` (+ ProductNotFound, OrderNotFound) -> 404
2. `ResourceConflictException` (+ ProductConflict, OrderConflict) -> 409
3. `BusinessRuleException` (+ InsufficientStock, Unorderable, DuplicateLineItem) -> 422
4. `ServiceUnavailableException` -> 503

### 3 Spring Data/JPA exception handlers (genuinely custom)
1. `ObjectOptimisticLockingFailureException` -> 409
2. `DataIntegrityViolationException` -> 409
3. `PessimisticLockingFailureException` -> 409

### 1 catch-all
- `Exception` -> 500 with message scrubbing

## Known Gaps

### Missing handlers (fall through to 500 catch-all)
- `DataAccessResourceFailureException` - DB down/connection refused (should be 503)
- `QueryTimeoutException` - query timeout (should be 503)
- `TransientDataAccessException` - transient DB failure (should be 503)
- `CannotAcquireLockException` - lock timeout (extends `PessimisticLockingFailureException` → `ConcurrencyFailureException` → `TransientDataAccessException`; handled by `ConcurrencyFailureException` → 409 via depth-based resolution)
- `TransactionException` / `TransactionSystemException` - tx infra failure (known gap — not in `DataAccessException` hierarchy, not all are transient; deferred to follow-up)
- `org.hibernate.exception.ConstraintViolationException` - different class from jakarta.validation (low risk, wrapped by Spring Data usually)
- `HttpMessageNotWritableException` - response serialization failure

### Brittleness issues
- Tests assert on title strings ("Validation Error" vs "Method Validation Error") which break when Spring changes validation dispatch path
- `@Size` on `idempotencyKey` in OrderApi triggers method-level validation for the entire createOrder method, changing which exception type Spring throws
- `@Order(HIGHEST_PRECEDENCE)` + catch-all `Exception` handler = any exception without a specific handler returns 500 (not Spring's default). This means 405/415/406 handlers CANNOT be removed — they'd be swallowed by the catch-all. Verified via bytecode: `ExceptionHandlerExceptionResolver.getExceptionHandlerMethod()` stops at the first `@ControllerAdvice` with any match.

### Cross-cutting enrichment applied to every response
- Custom `type` URI: `https://api.boot4ref.example.com/errors/{status}`
- `errorCode` property (derived from title)
- `timestamp` property
- `request` metadata (method, path, user-agent, request-id, protocol, scheme, isSecure)
- Dev-profile: exception class + truncated stack trace

## JHipster Approach (Spring Boot 4.0.2)
- Extends `ResponseEntityExceptionHandler` (inherits all built-in handlers)
- Single catch-all `@ExceptionHandler` + `handleExceptionInternal()` override
- Pipeline: `getProblemDetailWithCause()` -> `customizeProblem()` -> `toStatus()`
- Domain exceptions extend `ErrorResponseException` (carry their own ProblemDetail)
- Supports `@ResponseStatus` annotation resolution on exception classes
- Centralized production message scrubbing (package name detection)
- `getMappedStatus()` for exceptions where Spring defaults are wrong (ConcurrencyFailure -> 409)

---

## Spring Boot 4 Internal Mechanics (from bytecode analysis)

### ResponseEntityExceptionHandler architecture (Spring Framework 7.0.5)

`handleException()` is a single `@ExceptionHandler` declaring 20 exception types.
Internally it's an instanceof cascade. **Unrecognized exceptions are re-thrown** (`athrow`),
not swallowed.

### Two-track ProblemDetail resolution

**Track 1: Exceptions implementing `ErrorResponse`** (all Spring MVC exceptions + ErrorResponseException subclasses)
- Exception carries its own `ProblemDetail` via `getBody()`
- `handleExceptionInternal()` calls `ex.updateAndGetBody(messageSource, locale)` when body is null
- `createProblemDetail()` is NOT called — the exception IS the ProblemDetail source
- Includes i18n support via MessageSource lookup using auto-generated message codes

**Track 2: Non-ErrorResponse exceptions** (ConversionNotSupportedException, TypeMismatchException, HttpMessageNotReadable/Writable)
- Handler calls `createProblemDetail()` to build ProblemDetail from scratch
- Then passes it as body to `handleExceptionInternal()`

### handleExceptionInternal() — the single funnel

```
handleExceptionInternal(ex, body, headers, statusCode, request):
  1. If response already committed → return null
  2. If body == null AND ex instanceof ErrorResponse:
       body = ex.updateAndGetBody(messageSource, locale)   ← auto-resolves ProblemDetail
  3. If status == 500 AND body still null:
       store exception in request attributes (for error page)
  4. return createResponseEntity(body, headers, statusCode, request)
```

ALL roads lead through this method. This is THE override point for cross-cutting enrichment.

### ErrorResponseException — base class for domain exceptions

```
ErrorResponseException extends NestedRuntimeException implements ErrorResponse
  - Carries: HttpStatusCode, HttpHeaders, ProblemDetail
  - Constructor can accept pre-built ProblemDetail with title, detail, type URI, custom properties
  - updateAndGetBody() resolves i18n via MessageSource using auto-generated codes:
    - "problemDetail.type.<FQCN>" → type URI
    - "problemDetail.title.<FQCN>" → title
    - "problemDetail.<FQCN>" → detail (with message arguments)
  - If message code not found in MessageSource → field left unchanged (safe/non-destructive)
```

### createProblemDetail() hook

```
createProblemDetail(ex, statusCode, description, detailMessageCode, args, request):
  1. Uses ErrorResponse.builder(ex, statusCode, description)
  2. Sets optional detailMessageCode and args
  3. Builds ErrorResponse and calls updateAndGetBody(messageSource, locale)
  4. Returns ProblemDetail
```

**Important:** Only called for Track 2 exceptions. NOT for ErrorResponse exceptions.

---

## ~~Proposed Architecture~~ (REJECTED — see rationale below)

The following architecture was initially proposed but **rejected after adversarial review**:

```
ExceptionTranslator extends ResponseEntityExceptionHandler
├── Domain exceptions extend ErrorResponseException
├── Override handleExceptionInternal() for cross-cutting enrichment
└── Remove 7 redundant handlers, 4 domain handlers
```

### Why this was rejected

1. **`ErrorResponseException` has no `(String)` constructor** — all leaf exceptions
   (e.g., `ProductNotFoundException("Product not found")`) would break. Its simplest
   constructor is `(HttpStatusCode)`, coupling domain to HTTP.

2. **Spring's built-in `handleMethodArgumentNotValid` returns bare ProblemDetail** —
   `detail="Invalid request content."` with no field errors array. We'd lose 4 rich
   structured error handlers.

3. **Extending `ResponseEntityExceptionHandler` disables Spring Boot's auto-configured
   `ProblemDetailsExceptionHandler`** — any exception not handled by either falls to
   `BasicErrorController` (legacy format, not RFC 9457). See spring-boot#43850.

4. **Overriding `handleExceptionInternal()` trades one framework coupling for another** —
   only works if extending `ResponseEntityExceptionHandler` (which is rejected).

### Final decision: Conservative targeted refactor

See `docs/SPEC-exception-handling-refactor.md` for the full implementation spec.

Structural changes (Tasks 1-4):
1. Add `TransientDataAccessException` → 503 handler (new coverage)
2. Consolidate 2 lock handlers into 1 `ConcurrencyFailureException` → 409 (neutral message)
3. Add tests: transient handler + hierarchy routing proof
4. Fix test assertions: structural ProblemDetail assertions instead of title strings

Code quality improvements (Tasks 5-8):
5. Add `log.warn` to 4 validation/parse handlers (observability gap)
6. Scrub JSON parse detail (leaks Java type names); generic detail + dev-profile `$.parseError`
7. Extract `buildScrubbedErrorResponse` helper (eliminates fragile detail-override pattern)
8. Remove dead `crossParameterErrors` branch

Kept unchanged:
- `@Order(HIGHEST_PRECEDENCE)` — it's a feature
- Domain exceptions as plain `RuntimeException` — no coupling
- 405/415/406 handlers — cannot remove (catch-all would swallow them)
- 5 rich/custom handlers (validation, method validation, type mismatch, JSON parse,
  constraint violation)

### Spring Data exception hierarchy (critical for Task 1 + Task 2 interaction)

```
DataAccessException
├── NonTransientDataAccessException
│   └── DataIntegrityViolationException          → 409 (own handler)
└── TransientDataAccessException                 → 503 (new handler)
    ├── DataAccessResourceFailureException       → 503 (via TransientDataAccess)
    ├── QueryTimeoutException                    → 503 (via TransientDataAccess)
    └── ConcurrencyFailureException              → 409 (more-specific handler wins)
        ├── ObjectOptimisticLockingFailureException
        └── PessimisticLockingFailureException
            └── CannotAcquireLockException
```

Spring's `ExceptionHandlerMethodResolver` uses `ExceptionDepthComparator` to always
select the most-specific handler. `ConcurrencyFailureException` → 409 always wins
over `TransientDataAccessException` → 503 for concurrency exceptions. Declaration
order of `@ExceptionHandler` methods is irrelevant.
