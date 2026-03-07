# ExceptionTranslator Redesign: Deep Analysis & Starter Architecture

> **SUPERSEDED (2026-03-07):** Brainstorming document. Of the proposed 4-layer architecture,
> only Layer 1 was implemented: `@ProblemType` annotation (with `slug` and `title` fields only,
> no `status`/`scrubDetail`/`retryAfterSeconds`) and `ProblemPropertySource` interface. Layers
> 2–4 (`ProblemMappingRegistry`, enrichment pipeline, `ResponseEntityExceptionHandler` extension)
> were not implemented. The line numbers referencing ExceptionTranslator are from a pre-refactor
> version. See `ExceptionTranslator.java` for the current 16-handler implementation.

Redesigning ExceptionTranslator as a reusable starter for teams building Spring Boot 4 / Java 25 microservices. Three inputs analyzed: wimdeblauwe error-handling-spring-boot-starter, Spring Boot 4's built-in ProblemDetail support, and adversarial review of current implementation.

No code changes in this document. Brainstorming only.

---

## Part 1: wimdeblauwe Starter — What to Steal, What to Avoid

### Architecture

Annotation-driven, declarative exception-to-response mapping. Exceptions are self-documenting configuration objects. A single `@RestControllerAdvice` reads annotations at runtime and builds responses. Three-tier mapping: annotation > properties > convention (exception class name).

### Steal These Ideas

| Idea | Why It's Good |
|------|---------------|
| **Exception = configuration object** | HTTP status, error code, and response fields declared ON the exception class, not scattered across handler methods. Developers see the full contract when they define the exception. |
| **`@ResponseErrorProperty` on getters** | Reflection-based field harvesting. Mark a getter, it appears in the JSON response. Zero serialization code. |
| **Properties-based mapping for 3rd-party exceptions** | `error.handling.codes.org.springframework.dao.DataIntegrityViolationException=DATA_CONFLICT` — remap exceptions from libraries you don't control without wrapping them. |
| **Structured validation errors** | Automatic unpack of `BindingResult` into per-field `{code, property, message, rejectedValue}` array. |
| **Logging control via properties** | `error.handling.exception-logging=message_only` — single knob for dev vs prod verbosity. |

### Avoid These Mistakes

| Mistake | Why |
|---------|-----|
| **Custom response structure (`code` + `message`)** | Not RFC 9457. Clients parsing wimdeblauwe responses can't reuse logic for any other API. Once clients depend on `code`/`message`, migrating to `type`/`title`/`detail` is a breaking change. |
| **No `type` URI** | The whole point of RFC 9457's `type` is machine-readable disambiguation. wimdeblauwe's `code` field is convenient but non-standard. |
| **Reflection on every request** | Annotation scanning + getter reflection per exception throw. Not a bottleneck, but a compile-time approach (annotation processor or registry cache) is cleaner. |
| **No ProblemDetail integration** | Library predates Spring 6's ProblemDetail. Doesn't extend `ResponseEntityExceptionHandler`. Duplicates work the framework now does for free. |

### wimdeblauwe's Own Assessment

From his blog comparing with Spring 6 ProblemDetail: *"I find the use of the `code` field easier to work with... I really wonder how much projects will actually put a valid URI."* Pragmatic, but wrong for a reference starter. Standards compliance is the point.

---

## Part 2: Spring Boot 4 — What's Free Now

### ResponseEntityExceptionHandler Coverage

Spring Boot 4 (with `spring.mvc.problemdetails.enabled=true`) auto-handles these exceptions with RFC 9457 ProblemDetail responses — **zero custom code needed:**

| Exception | Status | Our Handler | Redundant? |
|-----------|--------|-------------|------------|
| `HttpRequestMethodNotSupportedException` | 405 | Lines 159-162 | **Yes** — framework handles identically |
| `HttpMediaTypeNotSupportedException` | 415 | Lines 165-168 | **Yes** |
| `HttpMediaTypeNotAcceptableException` | 406 | Lines 171-174 | **Yes** |
| `HttpMessageNotReadableException` | 400 | Lines 137-151 | **Partial** — framework gives basic 400, we add dev-profile `parseError` |
| `MethodArgumentNotValidException` | 400 | Lines 113-135 | **Partial** — framework gives basic field errors, we add enriched format |
| `HandlerMethodValidationException` | 400 | Lines 177-229 | **Partial** — framework handles, we add parameter/annotation detail |
| `MethodArgumentTypeMismatchException` | 400 | Lines 231-242 | **Partial** — framework gives basic 400, we add expectedType/invalidValue |
| `NoHandlerFoundException` | 404 | Not handled | Framework handles if enabled |
| `NoResourceFoundException` | 404 | Not handled | Framework handles (Servlet 6.0+) |
| `AsyncRequestTimeoutException` | 503 | Not handled | Framework handles |
| `MaxUploadSizeExceededException` | 413 | Not handled | Framework handles |

**Implication:** 3 of our 16 handlers are fully redundant. 4 more are partially redundant (framework handles the base case, we add enrichment). If we extend `ResponseEntityExceptionHandler`, we get all of these for free and only override the ones where we add value.

### Key Boot 4 Changes

1. **`HandlerMethodValidationException`** — new in Spring 7, replaces `MethodArgumentNotValidException` for method parameter validation. Both coexist but `HandlerMethodValidation` is preferred.

2. **MessageSource integration** — `ResponseEntityExceptionHandler` resolves `type`, `title`, `detail` through `MessageSource` for i18n. Free internationalization.

3. **`ErrorResponse.Builder`** — fluent builder for constructing `ErrorResponse` objects. Better than manual `ProblemDetail.forStatus()` + setters.

4. **`ErrorResponseException` base class** — domain exceptions CAN extend this to auto-render as ProblemDetail. But it's a Spring class — coupling domain exceptions to Spring is a design smell.

5. **ProblemDetail is NOT default-enabled** — still requires `spring.mvc.problemdetails.enabled=true`. Our `@RestControllerAdvice` with `HIGHEST_PRECEDENCE` intercepts before the auto-configured handler.

### Virtual Threads

No impact on exception handling. `ThreadLocal` works fine with virtual threads. No special considerations.

---

## Part 3: Current Implementation — Adversarial Findings

### Critical Issues

**1. `type` URI conflates HTTP status with problem type**

Current: `errorBaseUrl + status.value()` produces `https://api.boot4ref.example.com/errors/404`

Three different 409 exceptions (ResourceConflict, DataIntegrity, ConcurrencyFailure) produce the same `type` URI. A client cannot programmatically distinguish "you sent a duplicate" from "the database rejected a FK constraint". The entire purpose of `type` in RFC 9457 is to disambiguate problems sharing an HTTP status.

**2. `errorCode` derived from `title` string, not stable**

`title.toUpperCase().replace(" ", "_")` produces `"RESOURCE_NOT_FOUND"`. Renaming the title from `"Resource Not Found"` to `"Not Found"` silently breaks every client matching on `errorCode`. Error codes must be first-class constants.

**3. `addRequestMetadata` leaks internal details in production**

Every response includes `protocol`, `scheme`, `isSecure`, `userAgent` — in production. An attacker learns whether TLS terminates at the app or a reverse proxy. `User-Agent` reflection is an information echo. This metadata should be dev-only.

**4. `PessimisticLockingFailureException` not handled**

Falls to `Exception.class` catch-all, returns 500. Should be 409. Concrete bug.

**5. Exception hierarchy carries no structured data**

All domain exceptions carry only `String message`. The handler can't extract resource type, resource ID, or violation code without parsing the string. `InsufficientStockException`, `DuplicateLineItemException`, `UnorderableProductException` all map to generic `BUSINESS_RULE_VIOLATION`. Clients must parse `detail` text to discriminate.

### High Issues

**6. `buildScrubbedErrorResponse` is backwards**

Calls `buildErrorResponse` (sets `detail` to `ex.getMessage()` which may contain SQL), then overwrites with safe text. The unsafe detail exists briefly in the ProblemDetail object. Should never set the unsafe message in the first place.

**7. No `Retry-After` header on 503s**

`ServiceUnavailableException` and `TransientDataAccessException` both return 503 without `Retry-After`. RFC 9110 section 15.6.4: servers SHOULD send it.

**8. Validation handlers over-expose Spring internals**

`MethodArgumentNotValidException` response includes `objectName`, Spring's internal `errorCode` (like `"NotBlank"`), `bindingFailure` flag. Clients need `field`, `rejectedValue`, `message`. The rest couples clients to Spring's validation implementation.

**9. `rejectedValue` can leak PII**

If a user submits an email or phone in a field that fails validation, the rejected value is echoed in the response. No sanitization.

### Medium Issues

**10. `commons-lang3` for one method** — `ExceptionUtils.getStackTrace()` is the only usage. Java's `StringWriter`/`PrintWriter` does the same in 3 lines.

**11. `handleMethodValidation` annotation-sniffing** — 50 lines of procedural if-chain matching annotation types to strings. Java 25 `switch` with pattern matching would be cleaner. Bigger question: do clients need annotation types at all?

**12. No correlation ID** — `X-Request-Id` read from request, empty if client doesn't send one. Should generate server-side or use OTel trace ID.

**13. Stack trace truncation at character count** — Cuts mid-line at 5000 chars. Better to truncate by frame count.

### Test Coverage Gaps

- `DataIntegrityViolationException`: only tested via controller ITs, no direct unit test
- `PessimisticLockingFailureException`: no test exists
- Stack trace truncation: not tested
- Request metadata fields: existence checked, not individual values
- Logging levels: not asserted

---

## Part 4: Redesigned Architecture

### Core Principle

Marry wimdeblauwe's "exception = configuration" with RFC 9457 compliance and Spring Boot 4's built-in handlers. Exceptions declare their own mapping. The handler is a thin generic dispatcher plus enrichment pipeline. Teams extend by adding annotated exceptions, not by modifying the handler.

### Architecture Layers

```
Layer 4: Enrichment Pipeline ─── ProblemDetailEnricher beans (timestamp, correlation, debug, custom)
Layer 3: Generic Handler ──────── extends ResponseEntityExceptionHandler, single dispatch method
Layer 2: Problem Registry ─────── annotation scanner + properties loader, cached at startup
Layer 1: Exception Contracts ──── @ProblemType annotation + ApiException base class
```

### Layer 1: Exception Contracts

**`@ProblemType` annotation (replaces implicit title-to-errorCode mapping):**

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProblemType {
    int status();                         // HTTP status code
    String slug();                        // Stable identifier: "product-not-found"
    String title();                       // Human-readable: "Product Not Found"
    boolean scrubDetail() default false;  // If true, detail = title (never ex.getMessage())
    int retryAfterSeconds() default -1;   // -1 = no Retry-After header
}
```

**`@ProblemProperty` annotation (wimdeblauwe's best idea, adapted):**

```java
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ProblemProperty {
    String value() default "";  // Custom JSON key name; defaults to field/method name
}
```

**`ApiException` abstract base class:**

```java
public abstract class ApiException extends RuntimeException {
    protected ApiException(String message) { super(message); }
    protected ApiException(String message, Throwable cause) { super(message, cause); }
}
```

**Domain exception example — simple:**

```java
@ProblemType(status = 404, slug = "product-not-found", title = "Product Not Found")
public final class ProductNotFoundException extends ApiException {

    @ProblemProperty
    private final Long productId;

    public ProductNotFoundException(Long productId) {
        super("Product %d does not exist".formatted(productId));
        this.productId = productId;
    }

    public Long getProductId() { return productId; }
}
```

Response: `type` = `.../errors/product-not-found` (unique per problem, not per status), `errorCode` = `PRODUCT_NOT_FOUND` (from slug), response body includes `"productId": 42`.

**Domain exception example — business rule with structured fields:**

```java
@ProblemType(status = 422, slug = "insufficient-stock", title = "Insufficient Stock")
public final class InsufficientStockException extends ApiException {

    @ProblemProperty private final Long productId;
    @ProblemProperty private final int requested;
    @ProblemProperty private final int available;

    public InsufficientStockException(Long productId, int requested, int available) {
        super("Insufficient stock for product %d: requested %d, available %d"
                .formatted(productId, requested, available));
        this.productId = productId;
        this.requested = requested;
        this.available = available;
    }
    // getters...
}
```

Clients get `errorCode: INSUFFICIENT_STOCK` and structured fields `{productId, requested, available}` — no string parsing. Different 422 exceptions get different `type` URIs and error codes.

### Layer 2: Problem Registry

**Startup-time scanning + properties overlay. Cached, not computed per-request.**

```java
public class ProblemMappingRegistry {

    private final Map<Class<? extends Exception>, ProblemMapping> mappings = new ConcurrentHashMap<>();

    // Scan @ProblemType annotations on ApiException subclasses (classpath scan at startup)
    void register(Class<? extends ApiException> exClass) {
        ProblemType pt = exClass.getAnnotation(ProblemType.class);
        mappings.put(exClass, new ProblemMapping(
            pt.status(), pt.slug(), pt.title(), pt.scrubDetail(), pt.retryAfterSeconds()));
    }

    // Properties-based mapping for 3rd-party exceptions (wimdeblauwe idea)
    void registerFromProperties(ErrorHandlingProperties props) { ... }

    Optional<ProblemMapping> lookup(Class<? extends Exception> exClass) {
        // Walk class hierarchy until match found
    }
}
```

```java
public record ProblemMapping(
    int status, String slug, String title, boolean scrubDetail, int retryAfterSeconds
) {}
```

**Why a registry:**
- O(1) lookup per request (scanned once at startup)
- Properties can override annotations
- 3rd-party exceptions registered without wrapping
- Fail-fast: missing mappings detected at startup

### Layer 3: Generic Handler

**Extends `ResponseEntityExceptionHandler` — get 12+ Spring handlers for free:**

```java
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private final ProblemMappingRegistry registry;
    private final List<ProblemDetailEnricher> enrichers;
    private final ErrorHandlingProperties properties;

    // ── Domain exceptions: single generic handler ────────────────────

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest req) {
        ProblemMapping mapping = registry.lookup(ex.getClass())
                .orElseThrow(() -> new IllegalStateException(
                    "No @ProblemType for " + ex.getClass().getName()));

        ProblemDetail pd = buildProblemDetail(mapping, ex, req);
        harvestProperties(ex, pd);          // @ProblemProperty fields via reflection
        runEnrichers(pd, ex, req);
        logByStatus(mapping.status(), ex, req);
        return buildResponse(mapping, pd);
    }

    // ── Infrastructure exceptions: registry-based ────────────────────

    @ExceptionHandler({
        ConcurrencyFailureException.class,
        PessimisticLockingFailureException.class,  // was MISSING in current impl
        DataIntegrityViolationException.class,
        TransientDataAccessException.class
    })
    ResponseEntity<ProblemDetail> handleInfraException(Exception ex, HttpServletRequest req) {
        ProblemMapping mapping = registry.lookup(ex.getClass())
                .orElse(INTERNAL_SERVER_ERROR_FALLBACK);

        ProblemDetail pd = buildProblemDetail(mapping, ex, req);
        // detail always scrubbed (mapping.scrubDetail = true from properties)
        runEnrichers(pd, ex, req);
        logByStatus(mapping.status(), ex, req);
        return buildResponse(mapping, pd);
    }

    // ── Validation enrichment overrides ──────────────────────────────
    // Framework builds base ProblemDetail, we enrich with structured field errors

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        ProblemDetail pd = ex.getBody();
        pd.setType(URI.create(properties.getErrorBaseUrl() + "validation-error"));
        pd.setProperty("errorCode", "VALIDATION_ERROR");
        pd.setProperty("fieldErrors", ex.getFieldErrors().stream()
                .map(fe -> Map.of(
                    "field", fe.getField(),
                    "message", Objects.toString(fe.getDefaultMessage(), ""),
                    "rejectedValue", properties.isIncludeRejectedValues()
                            ? Objects.toString(fe.getRejectedValue(), "null") : "[redacted]"))
                .toList());
        runEnrichers(pd, ex, request);
        return ResponseEntity.status(status).headers(headers).body(pd);
    }

    // Similar streamlined override for handleHandlerMethodValidation
    // No annotation-sniffing. Just: parameter name + messages.

    // ── Catch-all ────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unexpected error", ex);
        ProblemDetail pd = buildProblemDetail(INTERNAL_SERVER_ERROR_FALLBACK, ex, req);
        runEnrichers(pd, ex, req);
        return ResponseEntity.status(500).body(pd);
    }

    // ── Private helpers ──────────────────────────────────────────────

    private ProblemDetail buildProblemDetail(ProblemMapping m, Exception ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatus(m.status());
        pd.setType(URI.create(properties.getErrorBaseUrl() + m.slug()));
        pd.setTitle(m.title());
        pd.setDetail(m.scrubDetail() ? m.title() : ex.getMessage());
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("errorCode", m.slug().toUpperCase().replace("-", "_"));
        return pd;
    }

    private ResponseEntity<ProblemDetail> buildResponse(ProblemMapping m, ProblemDetail pd) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(m.status());
        if (m.retryAfterSeconds() > 0) {
            builder.header("Retry-After", String.valueOf(m.retryAfterSeconds()));
        }
        return builder.body(pd);
    }

    private void harvestProperties(ApiException ex, ProblemDetail pd) {
        // Reflective scan of @ProblemProperty fields/getters, cached per class at startup
    }
}
```

### What Changed vs Current ExceptionTranslator

| Aspect | Current | Redesigned |
|--------|---------|------------|
| Spring handler inheritance | None (standalone `@RestControllerAdvice`) | Extends `ResponseEntityExceptionHandler` — 12+ free |
| Domain exception dispatch | One `@ExceptionHandler` per base class (4 methods) | Single `@ExceptionHandler(ApiException.class)` |
| Error code source | `title.toUpperCase().replace(" ", "_")` — fragile | `slug.toUpperCase().replace("-", "_")` — stable, from annotation |
| `type` URI | `errorBaseUrl + statusCode` — same for all 409s | `errorBaseUrl + slug` — unique per problem type |
| Structured fields in response | None, only `ex.getMessage()` | `@ProblemProperty` fields harvested automatically |
| Detail scrubbing | `buildScrubbedErrorResponse` (set unsafe, then overwrite) | `scrubDetail=true` on mapping — never sets unsafe text |
| Infra exceptions | 3 separate handlers, missing pessimistic lock | Single handler, registry-based, all lock types covered |
| `Retry-After` on 503 | Missing | From `@ProblemType(retryAfterSeconds=30)` or registry |
| Validation enrichment | 50+ lines custom from scratch | Override framework method, enrich base ProblemDetail |
| Request metadata | Always in response (production leak) | Dev-only via enricher |
| Correlation ID | `X-Request-Id` header or empty | OTel trace ID, fallback to UUID |
| Extension model | Fork the class | Annotated exceptions + enricher beans |
| 3rd-party mapping | Handler method per exception | Properties-based registration |
| Handler methods | 16 explicit | 4 (api, infra, catch-all, + selective overrides) |

### Layer 4: Enrichment Pipeline

```java
@FunctionalInterface
public interface ProblemDetailEnricher {
    void enrich(ProblemDetail pd, Exception ex, HttpServletRequest req);
}
```

**Built-in enrichers (registered as Spring beans, ordered):**

```java
// Always active
public class TimestampEnricher implements ProblemDetailEnricher {
    public void enrich(ProblemDetail pd, Exception ex, HttpServletRequest req) {
        pd.setProperty("timestamp", Instant.now());
    }
}

// Always active — OTel trace ID, fallback UUID
public class CorrelationIdEnricher implements ProblemDetailEnricher {
    public void enrich(ProblemDetail pd, Exception ex, HttpServletRequest req) {
        String traceId = Span.current().getSpanContext().getTraceId();
        if ("0000000000000000".equals(traceId)) {
            traceId = Optional.ofNullable(req.getHeader("X-Request-Id"))
                    .orElseGet(() -> UUID.randomUUID().toString());
        }
        pd.setProperty("traceId", traceId);
    }
}

// Dev profile only — replaces addDebugInfo + addRequestMetadata
public class DebugInfoEnricher implements ProblemDetailEnricher {
    private final boolean active;

    public DebugInfoEnricher(Environment env) {
        this.active = env.acceptsProfiles(Profiles.of("dev"));
    }

    public void enrich(ProblemDetail pd, Exception ex, HttpServletRequest req) {
        if (!active) return;
        pd.setProperty("exception", ex.getClass().getName());
        pd.setProperty("stackTrace", truncateByFrames(ex, 50));
        pd.setProperty("request", Map.of(
            "method", req.getMethod(),
            "path", req.getRequestURI(),
            "userAgent", Objects.toString(req.getHeader("User-Agent"), ""),
            "protocol", req.getProtocol()));
    }
}
```

**Teams add custom enrichers by registering a bean:**

```java
@Bean
ProblemDetailEnricher tenantEnricher() {
    return (pd, ex, req) -> pd.setProperty("tenantId", TenantContext.current());
}
```

---

## Part 5: Response Comparison

### Current (same `type` for all 409s, no structured fields, metadata leak)

```json
{
  "type": "https://api.boot4ref.example.com/errors/409",
  "title": "Resource Conflict",
  "status": 409,
  "detail": "Product with SKU ABC-123 already exists",
  "instance": "/api/products",
  "errorCode": "RESOURCE_CONFLICT",
  "timestamp": "2026-03-06T12:00:00Z",
  "request": {
    "httpMethod": "POST",
    "requestPath": "/api/products",
    "userAgent": "curl/8.1.2",
    "requestId": "",
    "protocol": "HTTP/1.1",
    "scheme": "http",
    "isSecure": "false"
  }
}
```

### Redesigned (unique `type`, structured fields, no metadata leak)

```json
{
  "type": "https://api.boot4ref.example.com/errors/product-conflict",
  "title": "Product Conflict",
  "status": 409,
  "detail": "Product with SKU ABC-123 already exists",
  "instance": "/api/products",
  "errorCode": "PRODUCT_CONFLICT",
  "timestamp": "2026-03-06T12:00:00Z",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "sku": "ABC-123"
}
```

### Redesigned — Business Rule (structured, discriminable)

```json
{
  "type": "https://api.boot4ref.example.com/errors/insufficient-stock",
  "title": "Insufficient Stock",
  "status": 422,
  "detail": "Insufficient stock for product 42: requested 100, available 5",
  "instance": "/api/orders",
  "errorCode": "INSUFFICIENT_STOCK",
  "timestamp": "2026-03-06T12:00:00Z",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "productId": 42,
  "requested": 100,
  "available": 5
}
```

### Redesigned — Scrubbed Infra (safe detail, Retry-After header)

```
HTTP/1.1 503 Service Unavailable
Retry-After: 30
Content-Type: application/problem+json

{
  "type": "https://api.boot4ref.example.com/errors/service-temporarily-unavailable",
  "title": "Service Temporarily Unavailable",
  "status": 503,
  "detail": "Service Temporarily Unavailable",
  "instance": "/api/orders",
  "errorCode": "SERVICE_TEMPORARILY_UNAVAILABLE",
  "timestamp": "2026-03-06T12:00:00Z",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

### Redesigned — Validation (clean, no Spring internals)

```json
{
  "type": "https://api.boot4ref.example.com/errors/validation-error",
  "title": "Bad Request",
  "status": 400,
  "detail": "Validation failed",
  "instance": "/api/products",
  "errorCode": "VALIDATION_ERROR",
  "timestamp": "2026-03-06T12:00:00Z",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "fieldErrors": [
    { "field": "name", "message": "must not be blank", "rejectedValue": "" },
    { "field": "price", "message": "must be greater than 0", "rejectedValue": "-5" }
  ]
}
```

No `objectName`, no `bindingFailure`, no Spring-internal `errorCode` like `"NotBlank"`.

---

## Part 6: Configuration Properties

```java
@ConfigurationProperties(prefix = "error-handling")
public record ErrorHandlingProperties(
    @NotNull String errorBaseUrl,
    LoggingMode loggingMode,
    boolean includeRejectedValues,
    int defaultRetryAfterSeconds,
    Map<String, MappingOverride> mappings
) {
    public enum LoggingMode { NONE, MESSAGE_ONLY, WITH_STACKTRACE }

    public record MappingOverride(
        int status, String slug, String title,
        @Nullable String detail, boolean scrubDetail
    ) {}
}
```

```properties
error-handling.error-base-url=https://api.myservice.example.com/errors/
error-handling.logging-mode=message-only
error-handling.include-rejected-values=true
error-handling.default-retry-after-seconds=30

# 3rd-party exception mappings (no wrapping needed)
error-handling.mappings.org.springframework.dao.DataIntegrityViolationException.status=409
error-handling.mappings.org.springframework.dao.DataIntegrityViolationException.slug=data-integrity-violation
error-handling.mappings.org.springframework.dao.DataIntegrityViolationException.title=Data Integrity Violation
error-handling.mappings.org.springframework.dao.DataIntegrityViolationException.scrub-detail=true

error-handling.mappings.org.springframework.dao.ConcurrencyFailureException.status=409
error-handling.mappings.org.springframework.dao.ConcurrencyFailureException.slug=concurrency-conflict
error-handling.mappings.org.springframework.dao.ConcurrencyFailureException.title=Concurrency Conflict
error-handling.mappings.org.springframework.dao.ConcurrencyFailureException.scrub-detail=true
```

---

## Part 7: Starter File Structure

```
com.example.errorhandling
├── annotation/
│   ├── ProblemType.java                  # @ProblemType(status, slug, title, scrubDetail, retryAfterSeconds)
│   └── ProblemProperty.java              # @ProblemProperty on exception fields/getters
├── ApiException.java                     # Abstract base class for domain exceptions
├── ProblemMapping.java                   # Record: status, slug, title, scrubDetail, retryAfterSeconds
├── ProblemMappingRegistry.java           # Annotation scanner + properties loader, cached at startup
├── ProblemDetailEnricher.java            # FunctionalInterface for enrichment pipeline
├── enricher/
│   ├── TimestampEnricher.java            # Always: adds timestamp
│   ├── CorrelationIdEnricher.java        # Always: OTel trace ID or UUID
│   └── DebugInfoEnricher.java            # Dev only: stack trace, request metadata
├── GlobalExceptionHandler.java           # extends ResponseEntityExceptionHandler
├── ErrorHandlingProperties.java          # @ConfigurationProperties(prefix = "error-handling")
└── ErrorHandlingAutoConfiguration.java   # Auto-config: registers handler, enrichers, registry
```

**Teams using the starter:**
1. Add dependency
2. Set `error-handling.error-base-url` in properties
3. Create exception classes with `@ProblemType` extending `ApiException`
4. Mark structured fields with `@ProblemProperty`
5. Optionally add custom `ProblemDetailEnricher` beans
6. Optionally override 3rd-party exception mappings in properties

No handler code. No `@RestControllerAdvice`. No `ProblemDetail` construction.

---

## Part 8: Open Design Questions

| # | Question | Recommendation |
|---|----------|----------------|
| 1 | Should `ApiException` allow direct instantiation? | Abstract. Every throwable problem needs a `@ProblemType`. Direct instantiation skips it. |
| 2 | `@ProblemProperty` on fields vs getters? | Both (like Jackson). Cache accessor per class at startup. |
| 3 | Should `errorCode` be separate from `slug`? | Derived by default (`slug.toUpperCase().replace("-", "_")`), explicit optional override in annotation. |
| 4 | Should infra mappings (ConcurrencyFailure, etc.) ship as defaults? | Yes. Convention over configuration. Override via properties. |
| 5 | Include `rejectedValue` in validation errors? | Default true. Property `error-handling.include-rejected-values=false` for teams handling PII. |
| 6 | Keep `commons-lang3` dependency? | Drop. Use `StringWriter`/`PrintWriter` or truncate by frame count. |
| 7 | Extend `ResponseEntityExceptionHandler` or standalone? | Extend. 12+ handlers free. Document which methods are overridden. |
| 8 | Classpath scanning for `@ProblemType` or manual registration? | Both. Auto-scan by default, manual `registry.register()` for edge cases. |
| 9 | Should the handler also support WebFlux? | Servlet-first. WebFlux adapter later if needed. |
| 10 | Should `@ProblemType` support message codes for i18n? | Yes, add optional `titleCode`/`detailCode` attributes. Resolve via `MessageSource` if present. |

---

## Part 9: Migration Path (if we proceed)

1. Introduce `@ProblemType`, `@ProblemProperty`, `ApiException` (non-breaking, additive)
2. Migrate domain exceptions one-by-one to annotations + structured fields
3. Introduce registry + enrichment pipeline
4. Switch handler to extend `ResponseEntityExceptionHandler`
5. Remove redundant handler methods (405, 415, 406)
6. Remove `commons-lang3` dependency
7. Move request metadata to dev-only enricher
8. Add `PessimisticLockingFailureException` handler
9. Add `Retry-After` headers on 503s
10. Update all tests

---

## Part 10: Adversarial Review

Independent adversarial analysis of this proposal. These findings should be resolved before implementation begins.

### Top 5 Deal-Breakers

**1. Over-Engineering**

4 layers, 12 files, a registry, classpath scanning, and an enrichment pipeline — for a problem that needs 1 annotation + 1 handler. The current `ExceptionTranslator` is 313 lines and handles everything. Most teams will have 5-10 custom exceptions. A `Map<Class, ProblemMapping>` in the handler constructor does the same job as the registry + scanner.

**2. Migration Breaks API Contracts**

Two breaking changes at once:
- `type` URI changes from `.../errors/404` to `.../errors/product-not-found`
- `errorCode` changes from `RESOURCE_NOT_FOUND` to `PRODUCT_NOT_FOUND`

Every client matching on these fields breaks. The proposal has no versioning strategy or backward-compatible migration path.

**3. Fail-Fast is a Lie**

The proposal claims "missing mappings detected at startup" but `@ExceptionHandler(ApiException.class)` catches at request time. If a developer forgets `@ProblemType`, the `orElseThrow` fires on the first thrown exception, not at boot. True fail-fast requires a `@PostConstruct` that scans all `ApiException` subclasses on the classpath and validates annotations exist.

**4. Forced `ApiException` Base Class**

Requiring all domain exceptions to extend `ApiException` means:
- Can't annotate existing exceptions from libraries
- Forces single-inheritance choice (what if exception already extends something?)
- The properties-based mapping exists for this case, but now there are two parallel dispatch systems

The annotation should work on ANY exception class, with `ApiException` as optional convenience.

**5. Filter-Chain & Security Exceptions**

Exceptions thrown before `DispatcherServlet` (servlet filters, Spring Security) never reach `@RestControllerAdvice`. Auth failures return Spring's default JSON while business errors return ProblemDetail — inconsistent for clients. Not addressed anywhere in the proposal.

### Additional Issues

**Architecture:**
- Enrichment pipeline (`List<ProblemDetailEnricher>`) adds ordering complexity and debugging difficulty for a feature that could be 3 method calls in the handler
- `ProblemMappingRegistry` is a reinvented `Map` with classpath scanning bolted on

**API Design:**
- `@ProblemProperty` via reflection on every throw — proposal says "cached at startup" but accessor caching ≠ value caching. Field *accessors* are cached, but *values* are read reflectively per instance per throw. Acceptable cost, but the proposal obscures this.
- `slug` naming collision risk — no uniqueness validation at startup. Two exceptions with `slug = "not-found"` silently overwrite each other in the registry.

**Missing Edge Cases:**
- No `Retry-After` on 429 (only 503)
- `ConstraintViolationException` (Bean Validation on service methods) not handled
- Nested exception unwrapping (e.g., `TransactionSystemException` wrapping `ConstraintViolationException`)
- `ResponseStatusException` — framework's own exception type, no integration point

**Performance:**
- Classpath scanning at startup adds boot time. For 5-10 exceptions, a static map is faster and simpler.

**Testing:**
- No guidance on how teams test their `@ProblemType` annotations. A test helper or archunit rule is needed.

**Operations:**
- Enricher pipeline ordering is implicit (Spring `@Order`). Debugging "why is my field missing" requires understanding bean ordering.

### Simpler Alternative Worth Considering

The same goals can be achieved with ~150 lines:
- Extend `ResponseEntityExceptionHandler`
- Populate a `Map<Class, ProblemMapping>` in the constructor (domain + infra mappings)
- One `@ExceptionHandler(Exception.class)` that does lookup + fallback
- Inline enrichment (timestamp, correlation ID) — no pipeline interface
- `@ProblemType` annotation for self-documenting exceptions, read at registration time (not per-request)

No scanner, no enricher interface, no registry class, no auto-configuration. Teams add exceptions to the map. Boring, debuggable, sufficient.

### Contradictions Within the Proposal



| Claim | Contradiction |
|-------|--------------|
| "Teams extend by adding annotated exceptions, not by modifying the handler" | Properties-based mapping requires modifying `application.properties` — a different kind of handler modification |
| "Convention over configuration" | 11-file starter with `@ConfigurationProperties`, `AutoConfiguration`, registry, scanner = heavy configuration |
| "Zero serialization code" via `@ProblemProperty` | Reflection IS serialization code — just hidden behind annotations |
| "O(1) lookup per request" | Class hierarchy walking (`Optional<ProblemMapping> lookup` walks superclasses) is O(depth), not O(1) |

### Verdict

The analysis of the current implementation's flaws (Part 3) is solid — those 13 findings are real bugs and design problems. The Spring Boot 4 free-handler analysis (Part 2) is accurate and actionable. The wimdeblauwe teardown (Part 1) correctly identifies what to steal.

The proposed architecture (Parts 4-7) over-corrects. It builds a framework where a pattern would suffice. Recommendation: fix the 13 findings in the current `ExceptionTranslator` (extend `ResponseEntityExceptionHandler`, fix `type` URIs, add `@ProblemType` annotation, handle `PessimisticLockingFailureException`, add `Retry-After`, move metadata to dev-only) without the registry/enricher/scanner/auto-config machinery.

---

## Part 11: SPEC Review — What Was Already Fixed

`SPEC-exception-handling-refactor.md` was written as a conservative, surgical alternative to the
full redesign. All 8 tasks were implemented in commits `a45a7cd` and `4fcb938`. This section
cross-references the SPEC's scope against the redesign's findings to identify what remains open.

### SPEC Task Execution Status

All tasks verified against the current `ExceptionTranslator.java` (313 lines) and test suite.

| SPEC Task | Status | Evidence |
|-----------|--------|----------|
| Task 1: `TransientDataAccessException` → 503 | Done | `handleTransientDataAccess` (lines 97-103), `buildScrubbedErrorResponse` |
| Task 2: Consolidate lock handlers → `ConcurrencyFailureException` | Done | Single `handleConcurrencyFailure` (lines 90-95), no separate optimistic/pessimistic |
| Task 3: TransientDataAccess test + hierarchy routing test | Done | `shouldReturn503WhenTransientDataAccessFailure`, `shouldReturn409Not503WhenConcurrencyFailure` in `ExceptionTranslatorTest` |
| Task 4: Fix brittle test assertions | Done | `OrderControllerTest` no longer asserts on framework-internal title strings |
| Task 5: Add logging to validation/parse handlers | Done | `log.warn` at lines 133, 149, 227, 241 |
| Task 6: Scrub JSON parse details | Done | Generic detail + `parseError` in dev profile only (lines 137-151) |
| Task 7: `buildScrubbedErrorResponse` helper | Done | Lines 266-272, used by ConcurrencyFailure, DataIntegrity, TransientDataAccess, catch-all |
| Task 8: Remove dead `crossParameterErrors` branch | Done | Zero matches in codebase |

### SPEC's Explicit Deferrals

The SPEC's decisions log flagged four items as out-of-scope future work:

1. **Structured `ConstraintViolation` data extraction** — `handleConstraintViolation` discards
   property paths, messages, and invalid values from `getConstraintViolations()`. Deferred because
   no active controller path throws `ConstraintViolationException` (only `ApplicationProperties`
   startup validation uses `@Validated`).

2. **`TransactionSystemException` handling** — DB-down during transaction begin surfaces as
   `TransactionSystemException` wrapping `CannotCreateTransactionException` → still 500. Not all
   `TransactionSystemException`s are transient (commit failures can be permanent), so distinguishing
   requires cause inspection. SPEC scoped to `DataAccessException` hierarchy only.

3. **No `Retry-After` on 503** — Both `ServiceUnavailableException` and
   `TransientDataAccessException` handlers omit `Retry-After`. SPEC deferred to address
   holistically across all 503 paths.

4. **`instance` URI not unique per occurrence** — `request.getRequestURI()` is shared across all
   errors on the same path. Appending a request ID would make it unique, but changes the API
   contract for every error response. Deferred as its own cross-cutting concern.

### Where SPEC and Adversarial Review Agree

Both analyses arrived at the same conclusions independently:

- **Do not extend `ResponseEntityExceptionHandler`.** SPEC proved via bytecode that it disables
  Spring Boot's auto-configured `ProblemDetailsExceptionHandler`, creating dual error format risk.
  The adversarial review flagged the catch-all interaction problem — same root cause, different angle.

- **Do not use `ErrorResponseException` as base class.** SPEC: domain coupling, no `(String)`
  constructor. Adversarial review: forced single-inheritance, can't annotate library exceptions.

- **`Retry-After` is a real gap.** Both identified this. Neither fixed it.

- **`type` URI is status-based, not problem-type-based.** Both identified this. The SPEC didn't
  fix it; the adversarial review correctly noted that fixing it is a breaking API change.

### Where the SPEC Was More Conservative (And Right)

The SPEC made several decisions that the redesign proposal contradicted. In each case, the SPEC's
bytecode-backed analysis holds:

- **Kept `@Order(HIGHEST_PRECEDENCE)` + catch-all + 405/415/406 handlers.** The redesign proposed
  removing "redundant" handlers. The SPEC proved via `ExceptionHandlerExceptionResolver` bytecode
  that removing them causes all 405/415/406 to return 500 — the catch-all swallows them before
  Spring's `ProblemDetailsExceptionHandler` gets a chance.

- **Kept `errorCode` derived from title.** The redesign wanted stable slugs. Valid improvement,
  but it's a breaking API change that the SPEC correctly scoped out of a "conservative refactor."

- **Kept `addRequestMetadata` in all profiles.** The redesign wanted dev-only. Valid security
  point, but the SPEC prioritized API contract stability. This remains an open item.

### Remaining Gaps (Not Covered by SPEC)

These are issues identified by the redesign (Part 3) or adversarial review (Part 10) that the
SPEC intentionally did not address. They form the backlog for a potential "SPEC v2":

| # | Gap | Source | Breaking? | Effort |
|---|-----|--------|-----------|--------|
| 1 | `type` URI is status-based (`.../errors/409`) — same for all 409s | Part 3 §1 | Yes | Medium |
| 2 | `errorCode` derived from title string — fragile if title changes | Part 3 §2 | Yes | Low |
| 3 | `addRequestMetadata` runs in production — information leak | Part 3 §3 | Yes (removes fields) | Low |
| 4 | Domain exceptions carry no structured data | Part 4 (Layer 1) | No (additive) | Medium |
| 5 | No correlation ID (OTel trace ID or fallback UUID) | Part 4 (Layer 4) | No (additive) | Low |
| 6 | `commons-lang3` dependency for one method | Part 3 §10 | No | Trivial |
| 7 | Filter-chain / Security exceptions inconsistent format | Part 10 §5 | No | Medium |
| 8 | `rejectedValue` can leak PII — no redaction option | Part 3 §9 | Yes (if redacted) | Low |
| 9 | No `Retry-After` on 503 responses | SPEC deferral §3 | No (additive header) | Low |
| 10 | `TransactionSystemException` → 500 (should be 503 for transient causes) | SPEC deferral §2 | No | Medium |
| 11 | `instance` URI not unique per occurrence | SPEC deferral §4 | Yes | Low |
| 12 | Stack trace truncation by char count, not frame count | Part 3 §13 | No | Trivial |

### Conclusion

The SPEC was a well-scoped surgical fix. All 8 tasks are fully implemented and verified. The
redesign document (Parts 1-9) identified real remaining gaps but proposed too much machinery to
fix them — the adversarial review (Part 10) confirmed this.

The path forward is a "SPEC v2" in the same conservative style: 6-10 targeted tasks addressing
items 1-12 above, grouped by breaking vs non-breaking changes. Non-breaking additions (correlation
ID, `Retry-After`, structured exception fields, drop `commons-lang3`) can ship independently.
Breaking changes (type URI, errorCode source, metadata removal, PII redaction) should be batched
into a single API contract revision with a clear migration guide.

---

## Part 12: Pragmatic Path Forward

### RFC 9457 Compliance Audit

The current API contracts are not valid RFC 9457. Three violations, verified against the code.

**Violation 1: `type` URI does not identify the problem type.**

RFC 9457 §3.1: *"The `type` member ... identifies the type of problem."* The purpose of `type` is
machine-readable disambiguation — a client reads `type` to decide how to handle the error
programmatically.

Current `createBaseProblemDetail` (line 279):
```java
problemDetail.setType(URI.create(errorBaseUrl + status.value()));
```

This produces `.../errors/409` for three different problem types:
- `ResourceConflictException` (duplicate resource)
- `DataIntegrityViolationException` (FK constraint violation)
- `ConcurrencyFailureException` (stale version / held lock)

A client receiving `.../errors/409` cannot distinguish "you sent a duplicate" from "the database
rejected a constraint" from "another request modified this resource." The `type` field is
functionally useless — it says nothing the `status` field doesn't already say.

Same problem at 400: `MethodArgumentNotValidException`, `HandlerMethodValidationException`,
`HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException`, and
`ConstraintViolationException` all produce `.../errors/400`.

**Violation 2: `type` URIs are not dereferenceable.**

RFC 9457 §3.1: *"When dereferenced, it SHOULD provide human-readable documentation for the
problem type."* `https://api.boot4ref.example.com/errors/409` returns nothing. This is a SHOULD,
not a MUST, but it means clients have no way to discover what a problem type means.

**Violation 3: `errorCode` substitutes for what `type` should do.**

The `errorCode` extension field (`RESOURCE_CONFLICT`, `VALIDATION_ERROR`, etc.) is doing the job
RFC 9457 designed `type` for — machine-readable problem identification. Clients matching on
`errorCode` are working around a broken `type`. This creates two parallel identification systems.

**What IS compliant:**
- `title`, `status`, `detail`, `instance` usage is correct
- Extension members (`timestamp`, `errors`, `validationErrors`, `request`) are valid per §3.2
- Scrubbed detail messages (safe text instead of raw `ex.getMessage()`) — correct use of `detail`
- Structured validation error arrays — valid and useful extensions

### What Each Input Contributes

Each of the three inputs (wimdeblauwe, Spring Boot 4, current class) solves a different part of
the problem. The pragmatic path takes the right piece from each.

**From wimdeblauwe: exception = configuration object.**

The best idea in the starter. An exception class should declare its own HTTP mapping — status,
slug, title. The handler reads this declaration. Teams define exceptions, not handler methods.

What to steal: the concept. One annotation per exception class.

What to skip: the custom response format (not RFC 9457), the reflection-on-every-request property
harvesting, the properties-based third-party mapping (handler methods are simpler for the ~5
infrastructure exception types that exist).

**From Spring Boot 4: don't fight the framework, but don't surrender to it.**

The SPEC proved via bytecode analysis:
- Extending `ResponseEntityExceptionHandler` disables Spring Boot's `ProblemDetailsExceptionHandler`
- `@Order(HIGHEST_PRECEDENCE)` + catch-all means 405/415/406 handlers MUST stay
- `ErrorResponseException` as domain base class couples domain to `org.springframework.web`

What to use: `ProblemDetail` as the response body (already doing this). `spring.mvc.problemdetails.enabled=true`
for any exception that somehow slips through (defense in depth).

What NOT to change: the handler's structural design. It works. The SPEC proved it.

**From the current class: the handler structure is sound.**

13 `@ExceptionHandler` methods covering infrastructure + domain + catch-all. The SPEC added 2 more
(transient, consolidated concurrency) and proved 3 cannot be removed (405/415/406). The handler
structure doesn't need architectural change. It needs:
- RFC 9457 compliance fixes (type URI, errorCode)
- Security fixes (metadata leak, PII in rejectedValue)
- Observability additions (correlation ID, Retry-After)
- A reusable pattern for domain exceptions (annotation + structured data)

### Design Decisions

| Decision | Rationale |
|----------|-----------|
| Fix `type` URI to use slugs, not status codes | RFC 9457 compliance. This is the single most important change. |
| Derive `errorCode` from slug, not title | Stable identifier. `slug.toUpperCase().replace("-", "_")` never changes unless the slug changes (which is a deliberate, versioned act). |
| Add `@ProblemType` annotation | Self-documenting exception→response mapping. One annotation, no framework. Handler reads it via `getClass().getAnnotation()` — cached per class at startup in a `Map`. |
| Add `ProblemPropertySource` interface | `Map<String, Object> problemProperties()` — opt-in structured data. No reflection, no scanning. Exception implements it; handler calls it. 3 lines of handler code. |
| Keep 4 explicit domain handler methods | `@ExceptionHandler(ResourceNotFoundException.class)`, etc. — same as current. The handler method checks for `@ProblemType` on the actual exception class (e.g., `ProductNotFoundException`) and uses it if present; falls back to the base class defaults. Other microservices add their own base classes + handler methods. |
| Do NOT add a generic `@ExceptionHandler(ApiException.class)` | Avoids the catch-all interaction problem. Each base exception class gets its own handler. Teams that copy this handler add ~5 lines per new base class. |
| Move `addRequestMetadata` to dev-only | Information leak. `protocol`, `scheme`, `isSecure`, `userAgent` echo is an attack surface. Dev profile already has debug info; metadata belongs there. |
| Add correlation ID | OTel `Span.current().getSpanContext().getTraceId()`, fallback to `X-Request-Id` header, fallback to UUID. Always present. |
| Add `Retry-After` on all 503 paths | RFC 9110 §15.6.4. Configurable via `ApplicationProperties`. Both `ServiceUnavailableException` and `TransientDataAccessException` handlers. |
| Drop `commons-lang3` | Only used for `ExceptionUtils.getStackTrace()`. Replace with `StringWriter`/`PrintWriter` (3 lines). Eliminates a production dependency for a dev-only feature. |
| Keep `@Order(HIGHEST_PRECEDENCE)` + catch-all + 405/415/406 | SPEC proved these are structurally required. Not negotiable. |
| Do NOT extend `ResponseEntityExceptionHandler` | SPEC proved this creates dual error format risk. Not negotiable. |
| Do NOT use `ErrorResponseException` as base class | Domain coupling. Not negotiable. |

### The Two Contracts

Two files. No Spring dependency. Any microservice can use them.

**`@ProblemType` — declares the exception→response mapping:**

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProblemType {
    int status();
    String slug();
    String title();
    boolean scrubDetail() default false;
}
```

Applied to leaf exceptions:
```java
@ProblemType(status = 422, slug = "insufficient-stock", title = "Insufficient Stock")
public final class InsufficientStockException extends BusinessRuleException
        implements ProblemPropertySource { ... }
```

The handler reads `@ProblemType` to produce:
- `type`: `errorBaseUrl + slug` → `.../errors/insufficient-stock` (unique, meaningful)
- `errorCode`: `INSUFFICIENT_STOCK` (from slug, stable)
- `title`: `"Insufficient Stock"` (from annotation)
- `status`: `422` (from annotation)

If the annotation is absent (e.g., a base class used directly), the handler falls back to its
hardcoded defaults — same as today's behavior. Annotation is opt-in enrichment.

**`ProblemPropertySource` — opt-in structured data:**

```java
public interface ProblemPropertySource {
    Map<String, Object> problemProperties();
}
```

Applied to exceptions that carry structured data:
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

The handler adds each entry to `ProblemDetail.setProperty()`. Clients get:

```json
{
  "type": "https://api.boot4ref.example.com/errors/insufficient-stock",
  "title": "Insufficient Stock",
  "status": 422,
  "detail": "Insufficient stock for product 42: requested 100, available 5",
  "instance": "/api/orders",
  "errorCode": "INSUFFICIENT_STOCK",
  "timestamp": "2026-03-06T12:00:00Z",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "productId": 42,
  "requested": 100,
  "available": 5
}
```

No string parsing. `productId`, `requested`, `available` are typed JSON values.

### Why Not `@ProblemProperty` (wimdeblauwe's Approach)

The redesign (Part 4) proposed `@ProblemProperty` annotations on exception fields, harvested via
reflection. `ProblemPropertySource` is simpler for three reasons:

1. **No reflection.** The exception implements a method that returns a map. The handler calls
   the method. Debuggable, visible in stack traces, zero magic.

2. **No caching infrastructure.** `@ProblemProperty` requires an accessor cache per exception
   class at startup (field handles, method handles). `ProblemPropertySource` is a method call.

3. **Explicit control.** The exception author decides exactly what appears in the response.
   With `@ProblemProperty`, a refactor that adds a getter could accidentally expose internal
   state. With `problemProperties()`, the response contract is explicit in one place.

The tradeoff: `ProblemPropertySource` requires ~3 lines of boilerplate per exception (`Map.of(...)`).
For the 3-5 domain exceptions in a typical microservice, this is negligible. For a service with 50
exception types, `@ProblemProperty` would save effort — but a service with 50 exception types has
bigger problems.

### What Changes in the Handler

The handler structure stays the same. The changes are in `createBaseProblemDetail` and the
domain handler methods.

**`createBaseProblemDetail` — slug-based type URI + correlation ID:**

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
    pd.setProperty("traceId", resolveTraceId(request));
    if (ex instanceof ProblemPropertySource source) {
        source.problemProperties().forEach(pd::setProperty);
    }
    addDebugInfo(pd, ex);
    return pd;
}
```

Key changes from current:
- Signature takes `slug` instead of title-only (slug drives `type` URI and `errorCode`)
- `type` URI: `errorBaseUrl + slug` instead of `errorBaseUrl + status.value()`
- `errorCode`: `slug.toUpperCase().replace("-", "_")` instead of `title.toUpperCase().replace(" ", "_")`
- Correlation ID: `traceId` always present
- `ProblemPropertySource` check: if exception implements it, properties are added
- `addRequestMetadata` removed (moved to `addDebugInfo`, dev-only)

**Domain handler methods — annotation-aware:**

```java
@ExceptionHandler(ResourceNotFoundException.class)
public ResponseEntity<ProblemDetail> handleResourceNotFound(
        ResourceNotFoundException ex, HttpServletRequest request) {
    return buildDomainErrorResponse(ex, HttpStatus.NOT_FOUND,
            "resource-not-found", "Resource Not Found", request);
}
```

Where `buildDomainErrorResponse` checks for `@ProblemType` on the actual exception class:

```java
private ResponseEntity<ProblemDetail> buildDomainErrorResponse(
        Exception ex, HttpStatus defaultStatus, String defaultSlug,
        String defaultTitle, HttpServletRequest request) {
    ProblemType pt = ex.getClass().getAnnotation(ProblemType.class);
    HttpStatus status = pt != null ? HttpStatus.valueOf(pt.status()) : defaultStatus;
    String slug = pt != null ? pt.slug() : defaultSlug;
    String title = pt != null ? pt.title() : defaultTitle;
    ProblemDetail pd = createBaseProblemDetail(status, slug, title, ex, request);
    // ... logging, return
}
```

When `ProductNotFoundException` has `@ProblemType(slug = "product-not-found", ...)`, the response
gets `type: .../errors/product-not-found` and `errorCode: PRODUCT_NOT_FOUND`. When the base
`ResourceNotFoundException` is thrown directly (no annotation), it falls back to
`type: .../errors/resource-not-found` and `errorCode: RESOURCE_NOT_FOUND`.

**Infrastructure handlers — fixed slugs, no annotation needed:**

```java
@ExceptionHandler(ConcurrencyFailureException.class)
public ResponseEntity<ProblemDetail> handleConcurrencyFailure(
        ConcurrencyFailureException ex, HttpServletRequest request) {
    return buildScrubbedErrorResponse(HttpStatus.CONFLICT, "concurrency-conflict",
            "Concurrency Conflict", "Concurrent modification conflict, please retry", ex, request);
}
```

Infrastructure exceptions come from Spring/JPA — no annotations possible. The handler hardcodes
the slug. This is fine. There are only 5 infrastructure exception types across all microservices.

### Response Contract Changes

Every error response changes. This is a breaking API revision driven by RFC 9457 compliance.

| Field | Before | After |
|-------|--------|-------|
| `type` | `.../errors/409` (status-based) | `.../errors/resource-conflict` (slug-based) |
| `errorCode` | `RESOURCE_CONFLICT` (title-derived) | `RESOURCE_CONFLICT` (slug-derived, same value but different source) |
| `traceId` | absent | always present (OTel trace ID / UUID) |
| `request` | always present (7 fields) | dev-only, merged into debug info |
| `Retry-After` header | absent on 503 | present on 503 (configurable seconds) |

For some exceptions, `errorCode` values change because the slug is more specific than the
old title-derived code:

| Exception | Old `errorCode` | New `errorCode` |
|-----------|-----------------|-----------------|
| `ProductNotFoundException` | `RESOURCE_NOT_FOUND` | `PRODUCT_NOT_FOUND` (if annotated) |
| `InsufficientStockException` | `BUSINESS_RULE_VIOLATION` | `INSUFFICIENT_STOCK` (if annotated) |
| `DataIntegrityViolationException` | `DATA_INTEGRITY_VIOLATION` | `DATA_INTEGRITY_VIOLATION` (unchanged) |
| `ConcurrencyFailureException` | `CONCURRENCY_CONFLICT` | `CONCURRENCY_CONFLICT` (unchanged) |

Unannotated exceptions keep their current `errorCode` values (the fallback slug is derived from
the same title string). Annotated exceptions get more specific codes. This is intentional —
the whole point is enabling clients to programmatically distinguish different problem types
sharing the same HTTP status.

### Exception Hierarchy After

```
RuntimeException
├── ResourceNotFoundException                       @ProblemType(404, "resource-not-found")
│   ├── ProductNotFoundException                    @ProblemType(404, "product-not-found")
│   └── OrderNotFoundException                      @ProblemType(404, "order-not-found")
├── ResourceConflictException                       @ProblemType(409, "resource-conflict")
│   ├── ProductConflictException                    @ProblemType(409, "product-conflict")
│   └── OrderConflictException                      @ProblemType(409, "order-conflict")
├── BusinessRuleException (abstract)                @ProblemType(422, "business-rule-violation")
│   ├── InsufficientStockException                  @ProblemType + ProblemPropertySource
│   ├── UnorderableProductException                 @ProblemType + ProblemPropertySource
│   └── DuplicateLineItemException                  @ProblemType + ProblemPropertySource
└── ServiceUnavailableException                     @ProblemType(503, "service-unavailable")

Spring/JPA (no annotation — handler hardcodes slugs):
├── ConcurrencyFailureException                     → "concurrency-conflict"
├── DataIntegrityViolationException                 → "data-integrity-violation"
├── TransientDataAccessException                    → "service-temporarily-unavailable"
├── MethodArgumentNotValidException                 → "validation-error"
├── HandlerMethodValidationException                → "validation-error"
├── HttpMessageNotReadableException                 → "malformed-json"
├── ConstraintViolationException                    → "constraint-violation"
├── MethodArgumentTypeMismatchException             → "invalid-path-variable"
├── HttpRequestMethodNotSupportedException          → "method-not-allowed"
├── HttpMediaTypeNotSupportedException              → "unsupported-media-type"
├── HttpMediaTypeNotAcceptableException             → "not-acceptable"
└── Exception (catch-all)                           → "internal-server-error"
```

### What Other Microservices Do

A payments service adopting this pattern:

1. Copy `@ProblemType` annotation and `ProblemPropertySource` interface (2 files, no Spring deps)
2. Copy `ExceptionTranslator` (infrastructure handlers are identical across services)
3. Define domain exception base classes with `@ProblemType`:
   ```java
   @ProblemType(status = 402, slug = "payment-declined", title = "Payment Declined")
   public class PaymentDeclinedException extends RuntimeException
           implements ProblemPropertySource { ... }
   ```
4. Add one handler method per base class (~5 lines each)
5. Set `app.error-base-url` in properties

Steps 1-2 are copy-paste. Step 3 is where domain modeling happens. Step 4 is boilerplate
but trivial. Step 5 is one line. No registry, no auto-configuration, no classpath scanning.

If this grows to 3+ services, extract steps 1-2 into a shared library. The library contains
the annotation, the interface, and the handler with infrastructure methods. Services add domain
handlers. This is the natural path to a starter — but only when the need is proven, not before.

### Implementation Phases

**Phase A: RFC 9457 compliance + security** (one atomic commit, breaking)

All breaking changes batched together. One migration event for clients.

- Slug-based `type` URIs in all handlers
- Slug-derived `errorCode` in `createBaseProblemDetail`
- `addRequestMetadata` → dev-only (merge into `addDebugInfo`)
- Correlation ID (`traceId`) in every response
- `Retry-After` header on both 503 handlers
- Drop `commons-lang3`, replace `ExceptionUtils.getStackTrace()` with `StringWriter`/`PrintWriter`
- Truncate stack traces by frame count (50), not char count (5000)
- Update all test assertions for new `type` URIs and `errorCode` values
- Update all test assertions that check for `$.request` in non-dev profile
- Add new test for `traceId` presence

**Phase B: Reusable domain exception pattern** (non-breaking, additive)

- Add `@ProblemType` annotation
- Add `ProblemPropertySource` interface
- Add `buildDomainErrorResponse` helper that reads annotation with fallback
- Refactor 4 domain handler methods to use `buildDomainErrorResponse`
- Annotate all 10 domain exception classes with `@ProblemType`
- Add `ProblemPropertySource` to `InsufficientStockException`, `UnorderableProductException`,
  `DuplicateLineItemException`
- Add tests verifying annotation-driven slug overrides and structured properties in responses
- No change to infrastructure handlers (they hardcode slugs — correct and simple)

Phase A and B can be separate commits but should be in the same release. Phase A is the contract
break; Phase B adds the reusable pattern on top. Splitting them makes code review easier.

### What This Is NOT

- Not a starter. Not a library. Not a framework. It's a handler + 2 contracts (annotation +
  interface) that any team can copy and extend.
- Not extending `ResponseEntityExceptionHandler`. Not using `ErrorResponseException`. Not
  removing 405/415/406 handlers. The SPEC's bytecode analysis stands.
- Not adding a registry, scanner, enricher pipeline, or auto-configuration. If you need those,
  you've outgrown the copy-and-extend pattern and should evaluate wimdeblauwe's starter
  (after it adds RFC 9457 support — currently it doesn't).
- Not handling filter-chain / Spring Security exceptions. Those are thrown before
  `DispatcherServlet` and never reach `@RestControllerAdvice`. A `Filter`-based error handler
  is a separate concern. Acknowledged as an out-of-scope gap.
