package com.example.boot4ref.common.rest;

import com.example.boot4ref.common.exception.BusinessRuleException;
import com.example.boot4ref.common.exception.ProblemPropertySource;
import com.example.boot4ref.common.exception.ProblemType;
import com.example.boot4ref.common.exception.ResourceConflictException;
import com.example.boot4ref.common.exception.ResourceNotFoundException;
import com.example.boot4ref.common.exception.ServiceUnavailableException;
import com.example.boot4ref.config.ApplicationProperties;
import io.opentelemetry.api.trace.Span;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Global exception handler translating exceptions into RFC 9457 Problem Details.
 * Handles domain exceptions, Spring validation errors, and HTTP errors.
 *
 * <p>Slug-based {@code type} URIs uniquely identify each problem type per RFC 9457 section 3.1.
 * Domain handlers read {@link ProblemType} annotations for leaf-exception specialization.
 * Infrastructure handlers (Spring/JPA exceptions) use hardcoded slugs.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ExceptionTranslator {

    private static final Logger log = LoggerFactory.getLogger(ExceptionTranslator.class);
    private static final int MAX_STACK_TRACE_FRAMES = 50;
    private static final String ERROR_CODE = "errorCode";
    private static final String TIMESTAMP = "timestamp";
    private static final String TRACE_ID_FIELD = "traceId";
    private static final String INVALID_TRACE_ID = "00000000000000000000000000000000";

    private final boolean isDevProfile;
    private final String errorBaseUrl;
    private final String retryAfterHeader;
    private final Map<Class<?>, Optional<ProblemType>> annotationCache = new ConcurrentHashMap<>();

    public ExceptionTranslator(Environment env, ApplicationProperties properties) {
        this.isDevProfile = env.acceptsProfiles(Profiles.of("dev"));
        this.errorBaseUrl = properties.getErrorBaseUrl();
        this.retryAfterHeader = String.valueOf(properties.getRetryAfterSeconds());
    }

    // -- Domain exception handlers --

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
        return withRetryAfter(buildDomainErrorResponse(ex, HttpStatus.SERVICE_UNAVAILABLE,
                "service-unavailable", "Service Unavailable", request));
    }

    // -- Infrastructure exception handlers --

    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ProblemDetail> handleConcurrencyFailure(
            ConcurrencyFailureException ex, HttpServletRequest request) {
        return buildScrubbedErrorResponse(HttpStatus.CONFLICT, "concurrency-conflict",
                "Concurrency Conflict", "Concurrent modification conflict, please retry",
                ex, request);
    }

    @ExceptionHandler(TransientDataAccessException.class)
    public ResponseEntity<ProblemDetail> handleTransientDataAccess(
            TransientDataAccessException ex, HttpServletRequest request) {
        return withRetryAfter(buildScrubbedErrorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                "service-temporarily-unavailable", "Service Temporarily Unavailable",
                "Database temporarily unavailable, please retry", ex, request));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        return buildScrubbedErrorResponse(HttpStatus.CONFLICT, "data-integrity-violation",
                "Data Integrity Violation",
                "Operation violates a data integrity constraint (e.g. referenced by other records)",
                ex, request);
    }

    // -- Validation / parse handlers --

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        ProblemDetail problemDetail = createBaseProblemDetail(
                HttpStatus.BAD_REQUEST, "request-body-validation-error",
                "Validation Error", ex, request);
        problemDetail.setProperty(
                "errors",
                ex.getBindingResult().getFieldErrors().stream()
                        .map(error -> Map.of(
                                "field", Optional.ofNullable(error.getField()).orElse("unknown"),
                                "objectName", Optional.ofNullable(error.getObjectName()).orElse("N/A"),
                                "rejectedValue",
                                        Optional.ofNullable(error.getRejectedValue())
                                                .map(Object::toString)
                                                .orElse("null"),
                                "message",
                                        Optional.ofNullable(error.getDefaultMessage()).orElse("No message"),
                                "errorCode", Optional.ofNullable(error.getCode()).orElse("UNKNOWN_CODE"),
                                "bindingFailure", String.valueOf(error.isBindingFailure())))
                        .toList());
        log.warn("{} {} -> 400 Validation Error", request.getMethod(), request.getRequestURI());
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleJsonParseError(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        ProblemDetail problemDetail = createBaseProblemDetail(
                HttpStatus.BAD_REQUEST, "malformed-json", "Malformed JSON", ex, request);
        problemDetail.setDetail("Malformed JSON request body");
        if (isDevProfile) {
            String rawDetail = Optional.ofNullable(ex.getMostSpecificCause())
                    .map(cause -> "JSON parsing error: " + cause.getMessage())
                    .orElse("Malformed JSON input: " + ex.getMessage());
            problemDetail.setProperty("parseError", rawDetail);
        }
        log.warn("{} {} -> 400 Malformed JSON", request.getMethod(), request.getRequestURI());
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "constraint-violation",
                "Constraint Violation", ex, request);
    }

    // -- HTTP error handlers (required: catch-all would swallow without these) --

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed",
                "Method Not Allowed", ex, request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type",
                "Unsupported Media Type", ex, request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ProblemDetail> handleMediaTypeNotAcceptable(
            HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_ACCEPTABLE, "not-acceptable",
                "Not Acceptable", ex, request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "missing-request-parameter",
                "Missing Request Parameter", ex, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResourceFound(
            NoResourceFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "no-resource-found",
                "No Resource Found", ex, request);
    }

    // -- Method validation handler --

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidation(
            HandlerMethodValidationException ex, HttpServletRequest request) {
        ProblemDetail problemDetail = createBaseProblemDetail(
                HttpStatus.BAD_REQUEST, "parameter-validation-error",
                "Validation Error", ex, request);

        List<Map<String, Object>> errors =
                ex.getParameterValidationResults().stream()
                        .map(result -> {
                            Map<String, Object> errorDetails = new HashMap<>();
                            MethodParameter param = result.getMethodParameter();

                            errorDetails.put(
                                    "parameter",
                                    Optional.ofNullable(param.getParameterName()).orElse("unknown"));
                            errorDetails.put("type", param.getParameterType().getSimpleName());

                            List<String> messages =
                                    result.getResolvableErrors().stream()
                                            .map(MessageSourceResolvable::getDefaultMessage)
                                            .filter(Objects::nonNull)
                                            .toList();
                            errorDetails.put("messages", messages);

                            result.getResolvableErrors().stream()
                                    .map(MessageSourceResolvable::getCodes)
                                    .filter(Objects::nonNull)
                                    .flatMap(Arrays::stream)
                                    .findFirst()
                                    .ifPresent(code -> errorDetails.put("errorCode", code));

                            Optional<String> annotation =
                                    Arrays.stream(param.getParameterAnnotations())
                                            .map(a -> {
                                                if (a.annotationType().equals(RequestParam.class))
                                                    return "RequestParam";
                                                if (a.annotationType().equals(RequestBody.class))
                                                    return "RequestBody";
                                                if (a.annotationType().equals(PathVariable.class))
                                                    return "PathVariable";
                                                return null;
                                            })
                                            .filter(Objects::nonNull)
                                            .findFirst();
                            errorDetails.put("annotation", annotation.orElse("N/A"));

                            return errorDetails;
                        })
                        .toList();

        problemDetail.setProperty("validationErrors", errors);
        log.warn("{} {} -> 400 Method Validation Error", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(ex.getStatusCode())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    // -- Type mismatch handler --

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        ProblemDetail problemDetail = createBaseProblemDetail(
                HttpStatus.BAD_REQUEST, "invalid-path-variable",
                "Invalid Path Variable", ex, request);
        problemDetail.setProperty("parameter", ex.getName());
        problemDetail.setProperty(
                "expectedType",
                ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "Unknown");
        problemDetail.setProperty("invalidValue", ex.getValue());
        log.warn("{} {} -> 400 Invalid Path Variable", request.getMethod(), request.getRequestURI());
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    // -- Catch-all --

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpectedException(
            Exception ex, HttpServletRequest request) {
        log.error("Unexpected error occurred", ex);
        return buildScrubbedErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-server-error", "Internal Server Error",
                "An unexpected internal error occurred", ex, request);
    }

    // -- Helper methods --

    private ResponseEntity<ProblemDetail> buildDomainErrorResponse(
            Exception ex, HttpStatus defaultStatus, String defaultSlug,
            String defaultTitle, HttpServletRequest request) {
        ProblemType pt = annotationCache
                .computeIfAbsent(ex.getClass(),
                        cls -> Optional.ofNullable(cls.getAnnotation(ProblemType.class)))
                .orElse(null);
        String slug = pt != null ? pt.slug() : defaultSlug;
        String title = pt != null ? pt.title() : defaultTitle;
        var response = buildErrorResponse(defaultStatus, slug, title, ex, request);
        if (ex instanceof ProblemPropertySource source) {
            source.problemProperties().forEach(Objects.requireNonNull(response.getBody())::setProperty);
        }
        return response;
    }

    private ResponseEntity<ProblemDetail> buildErrorResponse(
            HttpStatus status, String slug, String title,
            Exception ex, HttpServletRequest request) {
        ProblemDetail pd = createBaseProblemDetail(status, slug, title, ex, request);
        logAtLevel(status, title, request, ex);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    private ResponseEntity<ProblemDetail> buildScrubbedErrorResponse(
            HttpStatus status, String slug, String title, String safeDetail,
            Exception ex, HttpServletRequest request) {
        var response = buildErrorResponse(status, slug, title, ex, request);
        Objects.requireNonNull(response.getBody()).setDetail(safeDetail);
        return response;
    }

    private ResponseEntity<ProblemDetail> withRetryAfter(ResponseEntity<ProblemDetail> response) {
        return ResponseEntity.status(response.getStatusCode())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("Retry-After", retryAfterHeader)
                .body(response.getBody());
    }

    private void logAtLevel(HttpStatus status, String title, HttpServletRequest request,
            Exception ex) {
        if (status == HttpStatus.NOT_FOUND) {
            log.info("{} {} -> {} {}", request.getMethod(), request.getRequestURI(),
                    status.value(), title);
        } else if (status.is4xxClientError()) {
            log.warn("{} {} -> {} {}", request.getMethod(), request.getRequestURI(),
                    status.value(), title);
        } else if (status.is5xxServerError()) {
            log.error("{} {} -> {} {}", request.getMethod(), request.getRequestURI(),
                    status.value(), title, ex);
        }
    }

    private ProblemDetail createBaseProblemDetail(
            HttpStatus status, String slug, String title,
            Exception ex, HttpServletRequest request) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setTitle(title);
        pd.setDetail(ex.getMessage());
        pd.setType(URI.create(errorBaseUrl + slug));
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty(ERROR_CODE, slug.toUpperCase().replace("-", "_"));
        pd.setProperty(TIMESTAMP, Instant.now());
        pd.setProperty(TRACE_ID_FIELD, resolveTraceId(request));
        addDebugInfo(pd, ex, request);
        return pd;
    }

    private void addDebugInfo(ProblemDetail detail, Exception ex, HttpServletRequest request) {
        if (isDevProfile) {
            detail.setProperty("exception", ex.getClass().getName());
            var sw = new StringWriter();
            ex.printStackTrace(new PrintWriter(sw));
            detail.setProperty("stackTrace",
                    truncateStackTrace(sw.toString(), MAX_STACK_TRACE_FRAMES));
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

    private String resolveTraceId(HttpServletRequest request) {
        String otelTraceId = Span.current().getSpanContext().getTraceId();
        if (otelTraceId != null && !otelTraceId.equals(INVALID_TRACE_ID)) {
            return otelTraceId;
        }
        String requestId = request.getHeader("X-Request-Id");
        if (requestId != null && !requestId.isBlank()) {
            return requestId;
        }
        return UUID.randomUUID().toString();
    }

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
            return String.join("\n", Arrays.copyOf(lines, cutoff))
                    + "\n\t... " + (lines.length - cutoff) + " more lines";
        }
        return stackTrace;
    }
}
