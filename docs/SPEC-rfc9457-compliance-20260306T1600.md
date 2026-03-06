# SPEC: RFC 9457 Compliance + Reusable Exception Pattern

**Status: IMPLEMENTED** — all 13 tasks complete, 191 tests passing (0 failures).

## Review Resolutions

Review `REVIEW-rfc9457-spec-20260306T1434.md` identified 6 must-fix, 5 should-fix, 3 nice-to-have.
Resolutions applied during implementation:

| # | Issue | Resolution |
|---|-------|------------|
| F1 | 10 vs 11 count | Fixed to 11 |
| F2 | `@ProblemType` on abstract class dead without `@Inherited` | Added `@Inherited` to annotation |
| F3 | Status override risk | Removed `status()` from `@ProblemType` — handler determines status |
| D1/D2 | Task 5/10 collision | Merged Retry-After into final handler form; infrastructure handlers inline construction |
| D4 | Missing `application/problem+json` | Added `.contentType(MediaType.APPLICATION_PROBLEM_JSON)` to all response builders |
| S1/D3 | Shared `validation-error` slug | Split: `request-body-validation-error` / `parameter-validation-error` |
| S3 | `buildScrubbedErrorResponse` ordering | Accepted: transient in-memory only, never serialized |
| S4 | Testing gaps | Added traceId fallback test, Retry-After header tests, annotation/property tests |
| R2 | Phase B "non-breaking" label | Phase B IS breaking (annotated exceptions change `type`/`errorCode`) |

---

## Objective

Fix three RFC 9457 violations in the `ExceptionTranslator`, add missing observability and
security improvements, and introduce a lightweight reusable pattern for domain exceptions.

### What prompted this

SPEC v1 (8 tasks, commits `a45a7cd` + `4fcb938`) fixed coverage gaps, test brittleness, logging,
JSON scrubbing, and dead code. A 12-part redesign analysis (`docs/exception-translator-redesign.md`)
identified remaining RFC 9457 compliance violations and a reusable domain exception pattern. The
adversarial review (Part 10) rejected the full starter architecture but confirmed the findings.
Part 12 converged on a pragmatic path: fix RFC compliance + add two lightweight contracts.

### What this is NOT

- Not a starter, library, or framework. A handler + 2 contracts (annotation + interface).
- Not extending `ResponseEntityExceptionHandler`. SPEC v1 proved via bytecode this disables
  Spring Boot's auto-configured `ProblemDetailsExceptionHandler` (dual error format risk).
- Not using `ErrorResponseException` as domain base class. Domain coupling, no `(String)` constructor.
- Not removing 405/415/406 handlers. SPEC v1 proved catch-all swallows them.
- Not removing `@Order(HIGHEST_PRECEDENCE)`. Required for full error format control.
- Not adding a registry, scanner, enricher pipeline, or auto-configuration.
- Not handling filter-chain / Spring Security exceptions (thrown before `DispatcherServlet`).
- Not redacting `rejectedValue` PII (separate concern, needs opt-in redaction strategy).
- Not handling `TransactionSystemException` (not all causes are transient — needs cause inspection).

### Success criteria

Each criterion maps to one or more testable assertions.

1. Every handler produces a unique `type` URI based on a slug, not a status code
2. `errorCode` is derived from slug (`slug.toUpperCase().replace("-", "_")`) — stable across title renames
3. `addRequestMetadata` only runs in dev profile (merged into `addDebugInfo`)
4. Every error response includes a `traceId` field (OTel trace ID > `X-Request-Id` > UUID fallback)
5. Both 503 handlers include a `Retry-After` response header (configurable via `app.retry-after-seconds`)
6. `commons-lang3` dependency removed from production; `ExceptionUtils.getStackTrace()` replaced with `StringWriter`/`PrintWriter`
7. Stack traces truncated by frame count (50 frames) instead of char count (5000 chars)
8. `@ProblemType` annotation exists and is readable on all 11 domain exception classes
9. `ProblemPropertySource` interface exists and is implemented by 3 business rule exceptions
10. `buildDomainErrorResponse` reads `@ProblemType` with fallback to handler defaults
11. Annotated leaf exceptions produce specific `type` URIs and `errorCode` values
12. `ProblemPropertySource` properties appear as typed JSON fields in responses
13. `mvn test` passes with 0 failures

---

## Background

### RFC 9457 violations (from Part 12 analysis)

**Violation 1: `type` URI does not identify the problem type.**

RFC 9457 section 3.1: *"The `type` member ... identifies the type of problem."*

Current `createBaseProblemDetail` (line 279):
```java
problemDetail.setType(URI.create(errorBaseUrl + status.value()));
```

Three different 409 exceptions produce the same `type` URI (`.../errors/409`). Five different 400
exceptions produce the same `type` URI. A client cannot programmatically distinguish them.

**Violation 2: `type` URIs are not dereferenceable.**

RFC 9457 section 3.1: *"When dereferenced, it SHOULD provide human-readable documentation."*
Slug-based URIs (`.../errors/product-not-found`) are at least semantically meaningful even if not
dereferenceable. Status-based URIs (`.../errors/409`) are neither.

**Violation 3: `errorCode` substitutes for what `type` should do.**

Clients matching on `errorCode` are working around a broken `type`. Two parallel identification
systems where RFC 9457 designed one.

**What IS compliant (no changes needed):**
- `title`, `status`, `detail`, `instance` usage
- Extension members (`timestamp`, `errors`, `validationErrors`)
- Scrubbed detail messages on infrastructure exceptions
- Structured validation error arrays

### Security findings (from Part 3)

`addRequestMetadata` runs in all profiles. Every response includes `protocol`, `scheme`, `isSecure`,
`userAgent`. An attacker learns TLS termination point and gets User-Agent echo. Move to dev-only.

### Remaining gaps from SPEC v1 deferrals

- No `Retry-After` on 503 responses (RFC 9110 section 15.6.4 recommends it)
- `commons-lang3` imported for a single dev-only method
- Stack trace truncation at character count cuts mid-line

---

## Tech Stack

- Java 25, Spring Boot 4.0.3, Spring Framework 7.0.5
- OpenTelemetry (`spring-boot-starter-opentelemetry` + `opentelemetry-api-incubator`)
- Build/test: `mvn test` (Surefire), `mvn verify` (Failsafe)
- Test: JUnit 5, `@WebMvcTest`, MockMvc, Mockito

---

## Tasks

### Phase A: RFC 9457 Compliance + Security (Tasks 1-7)

All breaking changes batched together. One migration event for clients.

#### Task 1: Slug-based `type` URIs

**File:** `src/main/java/.../common/rest/ExceptionTranslator.java`

Change `createBaseProblemDetail` signature to accept a `slug` parameter. The `type` URI becomes
`errorBaseUrl + slug` instead of `errorBaseUrl + status.value()`.

```java
private ProblemDetail createBaseProblemDetail(
        HttpStatus status, String slug, String title, Exception ex, HttpServletRequest request) {
    ProblemDetail pd = ProblemDetail.forStatus(status);
    pd.setTitle(title);
    pd.setDetail(ex.getMessage());
    pd.setType(URI.create(errorBaseUrl + slug));
    pd.setInstance(URI.create(request.getRequestURI()));
    pd.setProperty(ERROR_CODE, slug.toUpperCase().replace("-", "_"));
    pd.setProperty(TIMESTAMP, Instant.now());
    // ... rest of method (correlation ID added in Task 4)
    addDebugInfo(pd, ex);
    return pd;
}
```

Update every caller of `createBaseProblemDetail` to pass the appropriate slug. See slug mapping
table below for the complete list.

Update `buildErrorResponse` and `buildScrubbedErrorResponse` signatures to take `slug` in addition
to `title`:

```java
private ResponseEntity<ProblemDetail> buildErrorResponse(
        HttpStatus status, String slug, String title, Exception ex, HttpServletRequest request) {
    ProblemDetail pd = createBaseProblemDetail(status, slug, title, ex, request);
    // ... logging
    return ResponseEntity.status(status).body(pd);
}

private ResponseEntity<ProblemDetail> buildScrubbedErrorResponse(
        HttpStatus status, String slug, String title, String safeDetail,
        Exception ex, HttpServletRequest request) {
    var response = buildErrorResponse(status, slug, title, ex, request);
    Objects.requireNonNull(response.getBody()).setDetail(safeDetail);
    return response;
}
```

**Validation:** Compile only — tests will fail until Task 7 (Phase A) updates assertions.

#### Task 2: Slug-derived `errorCode`

Part of Task 1's `createBaseProblemDetail` change. The `errorCode` derivation changes from:

```java
// Before (title-derived):
pd.setProperty(ERROR_CODE, title.toUpperCase().replace(" ", "_"));

// After (slug-derived):
pd.setProperty(ERROR_CODE, slug.toUpperCase().replace("-", "_"));
```

For most handlers, the resulting `errorCode` string is identical (e.g., slug `"resource-not-found"` →
`RESOURCE_NOT_FOUND`, same as old title `"Resource Not Found"` → `RESOURCE_NOT_FOUND`). Exceptions
where it changes:

| Handler | Old `errorCode` | New `errorCode` |
|---------|-----------------|-----------------|
| `handleJsonParseError` | `MALFORMED_JSON` | `MALFORMED_JSON` (same) |
| `handleTypeMismatch` | `INVALID_PATH_VARIABLE` | `INVALID_PATH_VARIABLE` (same) |
| `handleServiceUnavailable` | `SERVICE_UNAVAILABLE` | `SERVICE_UNAVAILABLE` (same) |
| `handleTransientDataAccess` | `SERVICE_TEMPORARILY_UNAVAILABLE` | `SERVICE_TEMPORARILY_UNAVAILABLE` (same) |

No `errorCode` values actually change in this codebase because slugs were derived from existing
titles. The change matters structurally — slug is now the source of truth, not title.

**Validation:** No separate validation — this is part of Task 1.

#### Task 3: Move `addRequestMetadata` into dev-only `addDebugInfo`

**File:** `src/main/java/.../common/rest/ExceptionTranslator.java`

Remove `addRequestMetadata` as a separate method. Merge its content into `addDebugInfo` inside the
`if (isDevProfile)` block:

```java
private void addDebugInfo(ProblemDetail detail, Exception ex, HttpServletRequest request) {
    if (isDevProfile) {
        detail.setProperty("exception", ex.getClass().getName());
        // stack trace (updated in Task 7)
        detail.setProperty("request", Map.of(
                "httpMethod", request.getMethod(),
                "requestPath", request.getRequestURI(),
                "userAgent", Optional.ofNullable(request.getHeader("User-Agent")).orElse(""),
                "requestId", Optional.ofNullable(request.getHeader("X-Request-Id")).orElse(""),
                "protocol", request.getProtocol(),
                "scheme", request.getScheme(),
                "isSecure", String.valueOf(request.isSecure())));
    }
}
```

Signature changes from `addDebugInfo(ProblemDetail, Exception)` to
`addDebugInfo(ProblemDetail, Exception, HttpServletRequest)`. Update `createBaseProblemDetail` to
pass `request` to `addDebugInfo` and remove the `addRequestMetadata` call.

**Breaking change:** `$.request` no longer present in non-dev profile responses. Tests asserting
`$.request` existence in `ExceptionTranslatorTest` (non-dev) will fail — fixed in Task 7 (Phase A).

**Validation:** Compile only — tests updated in Task 7 (Phase A).

#### Task 4: Add correlation ID (`traceId`)

**File:** `src/main/java/.../common/rest/ExceptionTranslator.java`

Add a `resolveTraceId` method:

```java
private static final String TRACE_ID_FIELD = "traceId";
private static final String INVALID_TRACE_ID = "00000000000000000000000000000000";

private String resolveTraceId(HttpServletRequest request) {
    String otelTraceId = io.opentelemetry.api.trace.Span.current()
            .getSpanContext().getTraceId();
    if (otelTraceId != null && !otelTraceId.equals(INVALID_TRACE_ID)) {
        return otelTraceId;
    }
    String requestId = request.getHeader("X-Request-Id");
    if (requestId != null && !requestId.isBlank()) {
        return requestId;
    }
    return java.util.UUID.randomUUID().toString();
}
```

Add to `createBaseProblemDetail`:
```java
pd.setProperty(TRACE_ID_FIELD, resolveTraceId(request));
```

**Imports to add:**
- `io.opentelemetry.api.trace.Span`

**Validation:** Compile only — new test added in Task 7 (Phase A).

#### Task 5: Add `Retry-After` header on 503 handlers

**File:** `src/main/java/.../config/ApplicationProperties.java`

Add a new property to the root `ApplicationProperties`:

```java
@NotNull
@Min(1)
private Integer retryAfterSeconds = 30;

public Integer getRetryAfterSeconds() { return retryAfterSeconds; }
public void setRetryAfterSeconds(Integer retryAfterSeconds) { this.retryAfterSeconds = retryAfterSeconds; }
```

**File:** `src/main/java/.../common/rest/ExceptionTranslator.java`

Store `retryAfterSeconds` in constructor:

```java
private final int retryAfterSeconds;

public ExceptionTranslator(Environment env, ApplicationProperties properties) {
    this.isDevProfile = env.acceptsProfiles(Profiles.of("dev"));
    this.errorBaseUrl = properties.getErrorBaseUrl();
    this.retryAfterSeconds = properties.getRetryAfterSeconds();
}
```

Update both 503 handlers to include the header:

```java
// handleServiceUnavailable:
var response = buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable",
        "Service Unavailable", ex, request);
return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header("Retry-After", String.valueOf(retryAfterSeconds))
        .body(response.getBody());

// handleTransientDataAccess — same pattern via buildScrubbedErrorResponse:
var pd = createBaseProblemDetail(HttpStatus.SERVICE_UNAVAILABLE,
        "service-temporarily-unavailable", "Service Temporarily Unavailable", ex, request);
pd.setDetail("Database temporarily unavailable, please retry");
// logging
return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header("Retry-After", String.valueOf(retryAfterSeconds))
        .body(pd);
```

Note: the 503 handlers can no longer delegate to `buildScrubbedErrorResponse` directly because
`ResponseEntity` from `buildScrubbedErrorResponse` doesn't carry the `Retry-After` header (the
header is on the response, not the body). Inline the ProblemDetail construction.

**File:** `src/main/resources/application.properties`

Add commented-out entry for documentation:

```properties
## Retry-After header value (seconds) for 503 responses
# app.retry-after-seconds=30
```

**Validation:** Compile only — tests in Task 7 (Phase A).

#### Task 6: Drop `commons-lang3`, replace with `StringWriter`/`PrintWriter`

**File:** `pom.xml`

Remove the `commons-lang3` dependency block (lines 103-107).

**File:** `src/main/java/.../common/rest/ExceptionTranslator.java`

Remove import:
```java
// REMOVE:
import org.apache.commons.lang3.exception.ExceptionUtils;
```

Add imports:
```java
import java.io.PrintWriter;
import java.io.StringWriter;
```

Replace `ExceptionUtils.getStackTrace(ex)` in `addDebugInfo` with:

```java
var sw = new StringWriter();
ex.printStackTrace(new PrintWriter(sw));
String fullStackTrace = sw.toString();
```

**Validation:** `mvn compile` — verify no compile errors from missing import.

#### Task 7: Truncate stack traces by frame count, not char count

**File:** `src/main/java/.../common/rest/ExceptionTranslator.java`

Replace the constant and truncation logic:

```java
// Before:
private static final int MAX_STACK_TRACE_LENGTH = 5000;
// ...
String truncatedStackTrace = fullStackTrace.length() > MAX_STACK_TRACE_LENGTH
        ? fullStackTrace.substring(0, MAX_STACK_TRACE_LENGTH) + "..."
        : fullStackTrace;

// After:
private static final int MAX_STACK_TRACE_FRAMES = 50;
// ...
String truncatedStackTrace = truncateStackTrace(fullStackTrace, MAX_STACK_TRACE_FRAMES);
```

Add a helper:

```java
private static String truncateStackTrace(String stackTrace, int maxFrames) {
    String[] lines = stackTrace.split("\n");
    int frameCount = 0;
    int cutoff = lines.length;
    for (int i = 0; i < lines.length; i++) {
        if (lines[i].stripLeading().startsWith("at ")) {
            frameCount++;
            if (frameCount > maxFrames) {
                cutoff = i;
                break;
            }
        }
    }
    if (cutoff < lines.length) {
        return String.join("\n", java.util.Arrays.copyOf(lines, cutoff))
                + "\n\t... " + (lines.length - cutoff) + " more lines";
    }
    return stackTrace;
}
```

**Validation:** `mvn test` after updating all test assertions below.

### Phase A test updates (part of Task 7)

All existing test assertions that reference old `type` URIs, old `errorCode` values, or `$.request`
in non-dev profile must be updated in one pass alongside Tasks 1-7.

**File:** `src/test/java/.../common/rest/ExceptionTranslatorTest.java`

Changes per test method:

| Test method | Change |
|-------------|--------|
| `shouldReturn404WithProblemDetailWhenResourceNotFound` | `$.type` → `.../errors/resource-not-found`; remove all `$.request.*` assertions; add `$.traceId` exists |
| `shouldReturn404WhenProductNotFoundExceptionThrown` | `$.type` → `.../errors/resource-not-found`; remove `$.request` exists; add `$.traceId` exists |
| `shouldReturn404WhenOrderNotFoundExceptionThrown` | `$.type` → `.../errors/resource-not-found`; add `$.traceId` exists |
| `shouldReturn409WithProblemDetailWhenResourceConflict` | `$.type` → `.../errors/resource-conflict`; remove `$.request` exists; add `$.traceId` exists |
| `shouldReturn409WhenProductConflictExceptionThrown` | `$.type` → `.../errors/resource-conflict` |
| `shouldReturn409WhenOrderConflictExceptionThrown` | `$.type` → `.../errors/resource-conflict` |
| `shouldReturn503WhenServiceUnavailable` | `$.type` → `.../errors/service-unavailable`; remove `$.request` exists; add `$.traceId` exists; add `Retry-After` header assertion |
| `shouldReturn400WithFieldErrorsWhenMethodArgumentNotValid` | `$.type` → `.../errors/validation-error`; add `$.traceId` exists |
| `shouldReturn400WhenConstraintViolation` | `$.type` → `.../errors/constraint-violation`; add `$.traceId` exists |
| `shouldReturn400WithValidationErrorsWhenHandlerMethodValidation` | `$.type` → `.../errors/validation-error` |
| `shouldReturn400WhenTypeMismatch` | `$.type` → `.../errors/invalid-path-variable` |
| `shouldReturn400WhenMalformedJson` | `$.type` → `.../errors/malformed-json` |
| `shouldReturn503WhenTransientDataAccessFailure` | `$.type` → `.../errors/service-temporarily-unavailable`; remove `$.request` exists; add `$.traceId` exists; add `Retry-After` header assertion |
| `shouldReturn409Not503WhenConcurrencyFailure` | no `$.type` assertion currently — add `$.type` → `.../errors/concurrency-conflict` |
| `shouldReturn500ForUnexpectedException` | `$.type` → `.../errors/internal-server-error`; add `$.traceId` exists |

**File:** `src/test/java/.../common/rest/ExceptionTranslatorDevProfileTest.java`

| Test method | Change |
|-------------|--------|
| `shouldIncludeStackTraceInDevProfile` | `$.type` → `.../errors/internal-server-error`; add `$.request` exists (now dev-only) |
| `shouldIncludeParseErrorInDevProfileForMalformedJson` | no change needed |

Add new test to verify `$.request` exists in dev profile:

```java
@Test
void shouldIncludeRequestMetadataInDevProfile() throws Exception {
    mockMvc.perform(get("/test/not-found")
                    .header("User-Agent", "JUnit")
                    .header("X-Request-Id", "req-456"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.request.httpMethod").value("GET"))
            .andExpect(jsonPath("$.request.userAgent").value("JUnit"))
            .andExpect(jsonPath("$.request.requestId").value("req-456"));
}
```

Add test for `Retry-After` header on 503:

```java
@Test
void shouldIncludeRetryAfterHeaderOn503() throws Exception {
    mockMvc.perform(get("/test/unavailable"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().exists("Retry-After"));
}
```

This test can go in `ExceptionTranslatorTest` (not dev-profile-specific).

**Import to add to `ExceptionTranslatorTest`:**
- `static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header`

**File:** `src/test/java/.../product/rest/ProductControllerTest.java`

Update any assertions on `$.type` for domain exceptions (e.g., `$.type` → `.../errors/resource-not-found`
for 404s, `.../errors/resource-conflict` for 409s). Remove `$.request` existence assertions if present.

**File:** `src/test/java/.../order/rest/OrderControllerTest.java`

Same pattern as `ProductControllerTest`.

**Validation:** `mvn test` — 0 failures.

---

### Phase B: Reusable Domain Exception Pattern (Tasks 8-13)

Additive changes. No breaking API changes beyond what Phase A already introduced.

#### Task 8: Add `@ProblemType` annotation

**New file:** `src/main/java/.../common/exception/ProblemType.java`

```java
package com.example.boot4ref.common.exception;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the RFC 9457 Problem Detail mapping for a domain exception.
 * The handler reads this annotation to produce slug-based type URIs,
 * stable errorCode values, and specific titles.
 *
 * Applied to exception classes. If absent, the handler falls back to
 * hardcoded defaults in the handler method.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProblemType {
    int status();
    String slug();
    String title();
}
```

No Spring dependency. Pure Java annotation.

**Validation:** `mvn compile`

#### Task 9: Add `ProblemPropertySource` interface

**New file:** `src/main/java/.../common/exception/ProblemPropertySource.java`

```java
package com.example.boot4ref.common.exception;

import java.util.Map;

/**
 * Opt-in interface for exceptions that carry structured data for RFC 9457 responses.
 * The handler calls problemProperties() and adds each entry to ProblemDetail.
 *
 * No Spring dependency. No reflection. The exception author controls exactly
 * what appears in the response.
 */
public interface ProblemPropertySource {
    Map<String, Object> problemProperties();
}
```

**Validation:** `mvn compile`

#### Task 10: Add `buildDomainErrorResponse` helper + annotation cache

**File:** `src/main/java/.../common/rest/ExceptionTranslator.java`

Add a `ConcurrentHashMap` cache for annotation lookups:

```java
private final Map<Class<?>, Optional<ProblemType>> annotationCache = new java.util.concurrent.ConcurrentHashMap<>();
```

Add the helper method:

```java
private ResponseEntity<ProblemDetail> buildDomainErrorResponse(
        Exception ex, HttpStatus defaultStatus, String defaultSlug,
        String defaultTitle, HttpServletRequest request) {
    ProblemType pt = annotationCache
            .computeIfAbsent(ex.getClass(),
                    cls -> Optional.ofNullable(cls.getAnnotation(ProblemType.class)))
            .orElse(null);
    HttpStatus status = pt != null ? HttpStatus.valueOf(pt.status()) : defaultStatus;
    String slug = pt != null ? pt.slug() : defaultSlug;
    String title = pt != null ? pt.title() : defaultTitle;
    ProblemDetail pd = createBaseProblemDetail(status, slug, title, ex, request);
    if (ex instanceof ProblemPropertySource source) {
        source.problemProperties().forEach(pd::setProperty);
    }
    if (status == HttpStatus.NOT_FOUND) {
        log.info("{} {} -> {} {}", request.getMethod(), request.getRequestURI(), status.value(), title);
    } else if (status.is4xxClientError()) {
        log.warn("{} {} -> {} {}", request.getMethod(), request.getRequestURI(), status.value(), title);
    } else if (status.is5xxServerError()) {
        log.error("{} {} -> {} {}", request.getMethod(), request.getRequestURI(), status.value(), title);
    }
    return ResponseEntity.status(status).body(pd);
}
```

**Import to add:**
- `com.example.boot4ref.common.exception.ProblemType`
- `com.example.boot4ref.common.exception.ProblemPropertySource`
- `java.util.concurrent.ConcurrentHashMap`

Refactor the 4 domain handler methods to use `buildDomainErrorResponse`:

```java
@ExceptionHandler(ResourceNotFoundException.class)
public ResponseEntity<ProblemDetail> handleResourceNotFound(
        ResourceNotFoundException ex, HttpServletRequest request) {
    return buildDomainErrorResponse(ex, HttpStatus.NOT_FOUND,
            "resource-not-found", "Resource Not Found", request);
}

@ExceptionHandler(ResourceConflictException.class)
public ResponseEntity<ProblemDetail> handleResourceConflict(
        ResourceConflictException ex, HttpServletRequest request) {
    return buildDomainErrorResponse(ex, HttpStatus.CONFLICT,
            "resource-conflict", "Resource Conflict", request);
}

@ExceptionHandler(BusinessRuleException.class)
public ResponseEntity<ProblemDetail> handleBusinessRule(
        BusinessRuleException ex, HttpServletRequest request) {
    return buildDomainErrorResponse(ex, HttpStatus.UNPROCESSABLE_ENTITY,
            "business-rule-violation", "Business Rule Violation", request);
}

@ExceptionHandler(ServiceUnavailableException.class)
public ResponseEntity<ProblemDetail> handleServiceUnavailable(
        ServiceUnavailableException ex, HttpServletRequest request) {
    // Special: needs Retry-After header, so can't fully delegate
    var pd = buildDomainErrorResponse(ex, HttpStatus.SERVICE_UNAVAILABLE,
            "service-unavailable", "Service Unavailable", request);
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", String.valueOf(retryAfterSeconds))
            .body(pd.getBody());
}
```

Infrastructure handlers (`handleConcurrencyFailure`, `handleDataIntegrityViolation`,
`handleTransientDataAccess`, catch-all) keep their hardcoded slugs — no annotation needed.

**Validation:** `mvn test` — all existing tests should pass (behavior unchanged when no annotations present).

#### Task 11: Annotate all 10 domain exception classes with `@ProblemType`

Apply `@ProblemType` to each exception class. Import: `com.example.boot4ref.common.exception.ProblemType`.

| Exception class | `@ProblemType` values |
|----------------|----------------------|
| `ResourceNotFoundException` | `status = 404, slug = "resource-not-found", title = "Resource Not Found"` |
| `ProductNotFoundException` | `status = 404, slug = "product-not-found", title = "Product Not Found"` |
| `OrderNotFoundException` | `status = 404, slug = "order-not-found", title = "Order Not Found"` |
| `ResourceConflictException` | `status = 409, slug = "resource-conflict", title = "Resource Conflict"` |
| `ProductConflictException` | `status = 409, slug = "product-conflict", title = "Product Conflict"` |
| `OrderConflictException` | `status = 409, slug = "order-conflict", title = "Order Conflict"` |
| `BusinessRuleException` | `status = 422, slug = "business-rule-violation", title = "Business Rule Violation"` |
| `InsufficientStockException` | `status = 422, slug = "insufficient-stock", title = "Insufficient Stock"` |
| `UnorderableProductException` | `status = 422, slug = "unorderable-product", title = "Unorderable Product"` |
| `DuplicateLineItemException` | `status = 422, slug = "duplicate-line-item", title = "Duplicate Line Item"` |

Note: `ServiceUnavailableException` also gets annotated:
| `ServiceUnavailableException` | `status = 503, slug = "service-unavailable", title = "Service Unavailable"` |

That's 11 classes total (4 base + 7 leaf).

**Validation:** `mvn compile`

#### Task 12: Add `ProblemPropertySource` to 3 business rule exceptions

**File:** `src/main/java/.../order/exception/InsufficientStockException.java`

Store constructor args as fields. Implement `ProblemPropertySource`:

```java
@ProblemType(status = 422, slug = "insufficient-stock", title = "Insufficient Stock")
public final class InsufficientStockException extends BusinessRuleException
        implements ProblemPropertySource {

    private final Long productId;
    private final int requested;
    private final int available;

    public InsufficientStockException(Long productId, int requested, int available) {
        super("Insufficient stock for product " + productId
                + ": requested " + requested + ", available " + available);
        this.productId = productId;
        this.requested = requested;
        this.available = available;
    }

    @Override
    public Map<String, Object> problemProperties() {
        return Map.of("productId", productId, "requested", requested, "available", available);
    }
}
```

**File:** `src/main/java/.../order/exception/UnorderableProductException.java`

```java
@ProblemType(status = 422, slug = "unorderable-product", title = "Unorderable Product")
public final class UnorderableProductException extends BusinessRuleException
        implements ProblemPropertySource {

    private final Long productId;
    private final ProductStatus status;

    public UnorderableProductException(Long productId, ProductStatus status) {
        super("Product " + productId + " is " + status + " and cannot be ordered");
        this.productId = productId;
        this.status = status;
    }

    @Override
    public Map<String, Object> problemProperties() {
        return Map.of("productId", productId, "productStatus", status.name());
    }
}
```

**File:** `src/main/java/.../order/exception/DuplicateLineItemException.java`

```java
@ProblemType(status = 422, slug = "duplicate-line-item", title = "Duplicate Line Item")
public final class DuplicateLineItemException extends BusinessRuleException
        implements ProblemPropertySource {

    private final Long productId;

    public DuplicateLineItemException(Long productId) {
        super("Duplicate product ID " + productId + " in order items");
        this.productId = productId;
    }

    @Override
    public Map<String, Object> problemProperties() {
        return Map.of("productId", productId);
    }
}
```

**Import to add to each:** `java.util.Map`, `com.example.boot4ref.common.exception.ProblemPropertySource`

**Validation:** `mvn compile`

#### Task 13: Update all tests for both phases

**File:** `src/test/java/.../common/rest/ExceptionTranslatorTest.java`

Add test endpoints for annotated leaf exceptions (if not already present — `ProductNotFoundException`
and `OrderNotFoundException` endpoints already exist):

Add a test for annotation-driven slug override:

```java
@Test
void shouldUseAnnotationSlugForProductNotFoundException() throws Exception {
    mockMvc.perform(get("/test/product-not-found"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/product-not-found"))
            .andExpect(jsonPath("$.title").value("Product Not Found"))
            .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"))
            .andExpect(jsonPath("$.traceId").exists());
}
```

Add a test for `ProblemPropertySource` — requires a new test endpoint:

```java
// In TestController:
@GetMapping("/test/insufficient-stock")
public void insufficientStock() {
    throw new InsufficientStockException(42L, 100, 5);
}
```

```java
@Test
void shouldIncludeStructuredPropertiesForBusinessRuleException() throws Exception {
    mockMvc.perform(get("/test/insufficient-stock"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.type").value(
                    "https://api.boot4ref.example.com/errors/insufficient-stock"))
            .andExpect(jsonPath("$.title").value("Insufficient Stock"))
            .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_STOCK"))
            .andExpect(jsonPath("$.productId").value(42))
            .andExpect(jsonPath("$.requested").value(100))
            .andExpect(jsonPath("$.available").value(5))
            .andExpect(jsonPath("$.traceId").exists());
}
```

Add a test for base class fallback (no annotation on `ResourceConflictException` base — wait, it
IS annotated). Test the fallback path: use `ResourceNotFoundException` directly (also annotated).
Fallback path only activates for unannotated exceptions — since all 11 are annotated, the fallback
path is tested via existing `shouldReturn404WithProblemDetailWhenResourceNotFound` (throws
`ResourceNotFoundException` which now has `@ProblemType`).

To verify fallback actually works, add a test with an unannotated exception:

```java
// In TestController:
@GetMapping("/test/business-rule-unannotated")
public void businessRuleUnannotated() {
    // Anonymous subclass without @ProblemType
    throw new BusinessRuleException("Custom rule violated") {};
}
```

Wait — `BusinessRuleException` is abstract. An anonymous subclass won't have `@ProblemType`. But
`BusinessRuleException` itself IS annotated. The annotation lookup checks `ex.getClass()` which
is the anonymous class, not `BusinessRuleException`. The fallback should trigger.

```java
@Test
void shouldFallbackToDefaultsWhenNoProblemTypeAnnotation() throws Exception {
    mockMvc.perform(get("/test/business-rule-unannotated"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.type").value(
                    "https://api.boot4ref.example.com/errors/business-rule-violation"))
            .andExpect(jsonPath("$.title").value("Business Rule Violation"))
            .andExpect(jsonPath("$.errorCode").value("BUSINESS_RULE_VIOLATION"));
}
```

**Import to add to `ExceptionTranslatorTest`:**
- `com.example.boot4ref.order.exception.InsufficientStockException`

Update existing tests that now get annotation-driven responses. For leaf exceptions:

| Test method | New `$.type` | New `$.title` | New `$.errorCode` |
|-------------|-------------|---------------|-------------------|
| `shouldReturn404WhenProductNotFoundExceptionThrown` | `.../errors/product-not-found` | `Product Not Found` | `PRODUCT_NOT_FOUND` |
| `shouldReturn404WhenOrderNotFoundExceptionThrown` | `.../errors/order-not-found` | `Order Not Found` | `ORDER_NOT_FOUND` |
| `shouldReturn409WhenProductConflictExceptionThrown` | `.../errors/product-conflict` | `Product Conflict` | `PRODUCT_CONFLICT` |
| `shouldReturn409WhenOrderConflictExceptionThrown` | `.../errors/order-conflict` | `Order Conflict` | `ORDER_CONFLICT` |

Update `ProductControllerTest` and `OrderControllerTest` similarly for any assertions on `$.type`,
`$.title`, or `$.errorCode` for annotated domain exceptions.

**Validation:** `mvn test` — 0 failures.

---

## Complete Slug Mapping Table

Every handler method → slug → `type` URI → `errorCode`.

| Handler method | Exception | Status | Slug | `type` URI | `errorCode` |
|---------------|-----------|--------|------|-----------|-------------|
| `handleResourceNotFound` | `ResourceNotFoundException` | 404 | `resource-not-found` | `.../errors/resource-not-found` | `RESOURCE_NOT_FOUND` |
| *(via annotation)* | `ProductNotFoundException` | 404 | `product-not-found` | `.../errors/product-not-found` | `PRODUCT_NOT_FOUND` |
| *(via annotation)* | `OrderNotFoundException` | 404 | `order-not-found` | `.../errors/order-not-found` | `ORDER_NOT_FOUND` |
| `handleResourceConflict` | `ResourceConflictException` | 409 | `resource-conflict` | `.../errors/resource-conflict` | `RESOURCE_CONFLICT` |
| *(via annotation)* | `ProductConflictException` | 409 | `product-conflict` | `.../errors/product-conflict` | `PRODUCT_CONFLICT` |
| *(via annotation)* | `OrderConflictException` | 409 | `order-conflict` | `.../errors/order-conflict` | `ORDER_CONFLICT` |
| `handleBusinessRule` | `BusinessRuleException` | 422 | `business-rule-violation` | `.../errors/business-rule-violation` | `BUSINESS_RULE_VIOLATION` |
| *(via annotation)* | `InsufficientStockException` | 422 | `insufficient-stock` | `.../errors/insufficient-stock` | `INSUFFICIENT_STOCK` |
| *(via annotation)* | `UnorderableProductException` | 422 | `unorderable-product` | `.../errors/unorderable-product` | `UNORDERABLE_PRODUCT` |
| *(via annotation)* | `DuplicateLineItemException` | 422 | `duplicate-line-item` | `.../errors/duplicate-line-item` | `DUPLICATE_LINE_ITEM` |
| `handleServiceUnavailable` | `ServiceUnavailableException` | 503 | `service-unavailable` | `.../errors/service-unavailable` | `SERVICE_UNAVAILABLE` |
| `handleConcurrencyFailure` | `ConcurrencyFailureException` | 409 | `concurrency-conflict` | `.../errors/concurrency-conflict` | `CONCURRENCY_CONFLICT` |
| `handleTransientDataAccess` | `TransientDataAccessException` | 503 | `service-temporarily-unavailable` | `.../errors/service-temporarily-unavailable` | `SERVICE_TEMPORARILY_UNAVAILABLE` |
| `handleDataIntegrityViolation` | `DataIntegrityViolationException` | 409 | `data-integrity-violation` | `.../errors/data-integrity-violation` | `DATA_INTEGRITY_VIOLATION` |
| `handleValidationException` | `MethodArgumentNotValidException` | 400 | `validation-error` | `.../errors/validation-error` | `VALIDATION_ERROR` |
| `handleMethodValidation` | `HandlerMethodValidationException` | 400 | `validation-error` | `.../errors/validation-error` | `VALIDATION_ERROR` |
| `handleJsonParseError` | `HttpMessageNotReadableException` | 400 | `malformed-json` | `.../errors/malformed-json` | `MALFORMED_JSON` |
| `handleConstraintViolation` | `ConstraintViolationException` | 400 | `constraint-violation` | `.../errors/constraint-violation` | `CONSTRAINT_VIOLATION` |
| `handleTypeMismatch` | `MethodArgumentTypeMismatchException` | 400 | `invalid-path-variable` | `.../errors/invalid-path-variable` | `INVALID_PATH_VARIABLE` |
| `handleMethodNotSupported` | `HttpRequestMethodNotSupportedException` | 405 | `method-not-allowed` | `.../errors/method-not-allowed` | `METHOD_NOT_ALLOWED` |
| `handleMediaTypeNotSupported` | `HttpMediaTypeNotSupportedException` | 415 | `unsupported-media-type` | `.../errors/unsupported-media-type` | `UNSUPPORTED_MEDIA_TYPE` |
| `handleMediaTypeNotAcceptable` | `HttpMediaTypeNotAcceptableException` | 406 | `not-acceptable` | `.../errors/not-acceptable` | `NOT_ACCEPTABLE` |
| `handleUnexpectedException` | `Exception` (catch-all) | 500 | `internal-server-error` | `.../errors/internal-server-error` | `INTERNAL_SERVER_ERROR` |

---

## Files Summary

### New files

| File | Task | Description |
|------|------|-------------|
| `src/main/java/.../common/exception/ProblemType.java` | 8 | `@ProblemType` annotation |
| `src/main/java/.../common/exception/ProblemPropertySource.java` | 9 | Structured data interface |

### Modified files

| File | Tasks | Nature of change |
|------|-------|------------------|
| `ExceptionTranslator.java` | 1-7, 10 | Slug-based type URIs, slug-derived errorCode, dev-only metadata, correlation ID, Retry-After, drop commons-lang3, frame-count truncation, `buildDomainErrorResponse` + annotation cache |
| `ApplicationProperties.java` | 5 | Add `retryAfterSeconds` property |
| `application.properties` | 5 | Commented-out `app.retry-after-seconds` |
| `pom.xml` | 6 | Remove `commons-lang3` dependency |
| `ResourceNotFoundException.java` | 11 | Add `@ProblemType` |
| `ProductNotFoundException.java` | 11 | Add `@ProblemType` |
| `OrderNotFoundException.java` | 11 | Add `@ProblemType` |
| `ResourceConflictException.java` | 11 | Add `@ProblemType` |
| `ProductConflictException.java` | 11 | Add `@ProblemType` |
| `OrderConflictException.java` | 11 | Add `@ProblemType` |
| `BusinessRuleException.java` | 11 | Add `@ProblemType` |
| `ServiceUnavailableException.java` | 11 | Add `@ProblemType` |
| `InsufficientStockException.java` | 11, 12 | Add `@ProblemType`, `ProblemPropertySource`, store fields |
| `UnorderableProductException.java` | 11, 12 | Add `@ProblemType`, `ProblemPropertySource`, store fields |
| `DuplicateLineItemException.java` | 11, 12 | Add `@ProblemType`, `ProblemPropertySource`, store field |
| `ExceptionTranslatorTest.java` | 7 (Phase A), 13 | Update all `$.type`/`$.errorCode`/`$.request` assertions, add traceId/Retry-After/annotation/property tests |
| `ExceptionTranslatorDevProfileTest.java` | 7 (Phase A) | Update `$.type`, add `$.request` dev-only test |
| `ProductControllerTest.java` | 7 (Phase A), 13 | Update `$.type`/`$.errorCode` for annotated exceptions |
| `OrderControllerTest.java` | 7 (Phase A), 13 | Update `$.type`/`$.errorCode` for annotated exceptions |

### Files NOT modified

- `OrderController.java`, `ProductController.java` (no handler changes)
- `OrderService.java`, `ProductService.java` (no service changes)
- Repository, outbox, Kafka code
- `AbstractIntegrationTest.java`, `TestcontainersConfiguration.java`
- Any Flyway migration

---

## Boundaries

### Always do

- Run `mvn test` after completing each phase (A and B). Within Phase A, Tasks 1-7 are an atomic
  batch — apply all before running tests. Within Phase B, run `mvn test` after Task 10 (verify
  fallback behavior with no annotations), then again after Tasks 11-13.
- Preserve all HTTP status code mappings (no status changes)
- Preserve structured error payloads (validation `errors[]`, `validationErrors[]`, type mismatch
  details, JSON parse `parseError` in dev)
- Keep `@Order(HIGHEST_PRECEDENCE)` on `ExceptionTranslator`
- Keep domain exceptions extending plain `RuntimeException`
- Keep 405/415/406 handlers (catch-all swallows them — SPEC v1 bytecode proof)

### Ask first

- Before modifying any file not listed in the files summary
- Before changing any handler's HTTP status code
- Before removing a handler that produces structured error data
- Before adding new dependencies to `pom.xml`

### Never do

- Do not extend `ResponseEntityExceptionHandler`
- Do not make domain exceptions extend `ErrorResponseException`
- Do not remove `@Order(HIGHEST_PRECEDENCE)`
- Do not remove 405/415/406 handlers
- Do not add a registry, scanner, enricher pipeline, or auto-configuration
- Do not handle filter-chain / Spring Security exceptions (out of scope)
- Do not touch outbox, Kafka, or repository code

---

## Conformance Checklist

After all tasks are complete, verify:

**Phase A — RFC 9457 Compliance:**

- [ ] Every handler passes a slug to `createBaseProblemDetail` (not a status code)
- [ ] `type` URIs use slugs: `.../errors/resource-not-found`, not `.../errors/404`
- [ ] `errorCode` is derived from slug: `slug.toUpperCase().replace("-", "_")`
- [ ] `addRequestMetadata` is removed as a separate method; content merged into `addDebugInfo`
- [ ] `$.request` absent in non-dev profile responses
- [ ] `$.request` present in dev profile responses
- [ ] Every response includes `$.traceId` field
- [ ] `traceId` resolves OTel trace ID > `X-Request-Id` > UUID
- [ ] Both 503 handlers include `Retry-After` response header
- [ ] `app.retry-after-seconds` property exists in `ApplicationProperties` with default 30
- [ ] `commons-lang3` removed from `pom.xml`
- [ ] No `import org.apache.commons.lang3` in any source file
- [ ] Stack traces truncated by frame count (50), not char count
- [ ] `MAX_STACK_TRACE_LENGTH` constant removed, replaced with `MAX_STACK_TRACE_FRAMES`

**Phase B — Reusable Pattern:**

- [ ] `@ProblemType` annotation exists in `common.exception` package
- [ ] `ProblemPropertySource` interface exists in `common.exception` package
- [ ] `buildDomainErrorResponse` helper reads `@ProblemType` with fallback
- [ ] Annotation cache is `ConcurrentHashMap` (thread-safe, computed once per class)
- [ ] All 11 domain exception classes annotated with `@ProblemType`
- [ ] `InsufficientStockException` implements `ProblemPropertySource` with `productId`, `requested`, `available`
- [ ] `UnorderableProductException` implements `ProblemPropertySource` with `productId`, `productStatus`
- [ ] `DuplicateLineItemException` implements `ProblemPropertySource` with `productId`
- [ ] Annotated leaf exceptions produce specific `type` URIs (e.g., `.../errors/product-not-found`)
- [ ] `ProblemPropertySource` fields appear as typed JSON values in responses
- [ ] Unannotated exception subclasses fall back to handler defaults

**Cross-cutting:**

- [ ] `ExceptionTranslator` still has `@Order(HIGHEST_PRECEDENCE)`
- [ ] `ExceptionTranslator` does NOT extend `ResponseEntityExceptionHandler`
- [ ] All domain exceptions still extend `RuntimeException` (not `ErrorResponseException`)
- [ ] 405/415/406 handlers preserved
- [ ] 5 rich/custom handlers preserved (validation, method validation, type mismatch, JSON parse, constraint violation)
- [ ] `mvn test` passes with 0 failures

---

## Decisions Log

| Decision | Rationale | Source |
|----------|-----------|--------|
| Fix `type` URI to use slugs | RFC 9457 section 3.1 requires `type` to identify the problem type. Status-based URIs make `type` functionally useless — identical to `status`. | Part 3 §1, Part 12 |
| Derive `errorCode` from slug | Slug is a stable, first-class identifier. Title is a display string that can change. `slug.toUpperCase().replace("-", "_")` is deterministic and immune to title renames. | Part 3 §2, Part 12 |
| Move `addRequestMetadata` to dev-only | `protocol`, `scheme`, `isSecure` reveal TLS termination point. `userAgent` echo is information reflection. Attack surface with zero client utility. | Part 3 §3, Part 12 |
| Add correlation ID | Error responses need a correlation handle for support workflows. OTel trace ID is already propagated through the app. Fallback chain ensures a value is always present. | Part 3 §12, Part 12 |
| Add `Retry-After` on 503 | RFC 9110 section 15.6.4 recommends it. Both existing 503 handlers lack it. Configurable via `ApplicationProperties` for operational tuning. | Part 12, SPEC v1 deferral §3 |
| Drop `commons-lang3` | Single usage: `ExceptionUtils.getStackTrace()`, a 3-line replacement with `StringWriter`/`PrintWriter`. Eliminates a production dependency for a dev-only feature. | Part 3 §10, Part 12 |
| Frame-count truncation | Character-count truncation (5000 chars) cuts stack traces mid-line, producing unreadable output. Frame counting (50 `at ` lines) always cuts at line boundaries. | Part 3 §13, Part 12 |
| `@ProblemType` annotation | Self-documenting exception→response mapping. One annotation per class, no framework. Handler reads via `getClass().getAnnotation()` with `ConcurrentHashMap` cache. | Part 12, wimdeblauwe |
| `ProblemPropertySource` over `@ProblemProperty` | No reflection, no caching infrastructure, explicit control. Exception author decides exactly what appears in the response via a `Map.of()` return. 3 lines of boilerplate per exception. | Part 12 |
| Keep 4 explicit domain handler methods | Each base exception class gets its own `@ExceptionHandler`. Annotation provides opt-in specialization for leaf classes. Avoids catch-all interaction problems from a generic `@ExceptionHandler(RuntimeException.class)`. | Part 12, SPEC v1 |
| Infrastructure handlers hardcode slugs | `ConcurrencyFailureException`, `DataIntegrityViolationException`, etc. come from Spring/JPA — no annotations possible. Handler hardcodes slug. Only ~5 types, never changes. | Part 12 |
| Do not extend `ResponseEntityExceptionHandler` | Disables Spring Boot's auto-configured `ProblemDetailsExceptionHandler`, creating dual error format risk. Verified via bytecode in SPEC v1. | SPEC v1, Part 10 |
| Do not use `ErrorResponseException` as base class | No `(String)` constructor. Forces single-inheritance. Couples domain to `org.springframework.web`. | SPEC v1, Part 10 |
| Keep `@Order(HIGHEST_PRECEDENCE)` + catch-all + 405/415/406 | Structurally required. Removing 405/415/406 handlers causes them to return 500 (catch-all swallows before Spring's `ProblemDetailsExceptionHandler`). Verified via bytecode in SPEC v1. | SPEC v1 |
| Batch all breaking changes in Phase A | One migration event for clients. `type` URI, `errorCode` source, metadata removal — all in one commit. Phase B is additive (new annotations, new response fields). | Part 12 |
| Not handling filter-chain exceptions | Thrown before `DispatcherServlet`, never reach `@RestControllerAdvice`. Requires a `Filter`-based handler — separate concern. | Part 10 §5 |
| Not redacting `rejectedValue` PII | Needs an opt-in redaction strategy (not all fields contain PII). Separate spec with its own design decisions. | Part 3 §9 |
