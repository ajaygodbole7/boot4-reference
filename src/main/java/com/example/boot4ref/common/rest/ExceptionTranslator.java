package com.example.boot4ref.common.rest;

import com.example.boot4ref.common.exception.BusinessRuleException;
import com.example.boot4ref.common.exception.ResourceConflictException;
import com.example.boot4ref.common.exception.ResourceNotFoundException;
import com.example.boot4ref.common.exception.ServiceUnavailableException;
import com.example.boot4ref.config.ApplicationProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Global exception handler translating exceptions into RFC 9457 Problem Details.
 * Handles domain exceptions, Spring validation errors, and HTTP errors.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ExceptionTranslator {

    private static final Logger log = LoggerFactory.getLogger(ExceptionTranslator.class);
    private static final int MAX_STACK_TRACE_LENGTH = 5000;
    private static final String ERROR_CODE = "errorCode";
    private static final String TIMESTAMP = "timestamp";
    private final boolean isDevProfile;
    private final String errorBaseUrl;

    public ExceptionTranslator(Environment env, ApplicationProperties properties) {
        this.isDevProfile = env.acceptsProfiles(Profiles.of("dev"));
        this.errorBaseUrl = properties.getErrorBaseUrl();
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleResourceNotFound(
            ResourceNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "Resource Not Found", ex, request);
    }

    @ExceptionHandler(ResourceConflictException.class)
    public ResponseEntity<ProblemDetail> handleResourceConflict(
            ResourceConflictException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, "Resource Conflict", ex, request);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ProblemDetail> handleBusinessRule(
            BusinessRuleException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.UNPROCESSABLE_ENTITY, "Business Rule Violation", ex, request);
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleServiceUnavailable(
            ServiceUnavailableException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", ex, request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        var response = buildErrorResponse(HttpStatus.CONFLICT, "Optimistic Lock Conflict", ex, request);
        Objects.requireNonNull(response.getBody()).setDetail("Resource was modified by another request");
        return response;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        var response = buildErrorResponse(HttpStatus.CONFLICT, "Data Integrity Violation", ex, request);
        Objects.requireNonNull(response.getBody())
                .setDetail("Operation violates a data integrity constraint (e.g. referenced by other records)");
        return response;
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handlePessimisticLock(
            PessimisticLockingFailureException ex, HttpServletRequest request) {
        var response = buildErrorResponse(HttpStatus.CONFLICT, "Resource Busy", ex, request);
        Objects.requireNonNull(response.getBody()).setDetail("Resource is temporarily locked, please retry");
        return response;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        ProblemDetail problemDetail =
                createBaseProblemDetail(HttpStatus.BAD_REQUEST, "Validation Error", ex, request);
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
        return ResponseEntity.badRequest().body(problemDetail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleJsonParseError(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        ProblemDetail problemDetail =
                createBaseProblemDetail(HttpStatus.BAD_REQUEST, "Malformed JSON", ex, request);
        String errorDetail =
                Optional.ofNullable(ex.getMostSpecificCause())
                        .map(cause -> "JSON parsing error: " + cause.getMessage())
                        .orElse("Malformed JSON input: " + ex.getMessage());
        problemDetail.setDetail(errorDetail);
        return ResponseEntity.badRequest().body(problemDetail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Constraint Violation", ex, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed", ex, request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type", ex, request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ProblemDetail> handleMediaTypeNotAcceptable(
            HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_ACCEPTABLE, "Not Acceptable", ex, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidation(
            HandlerMethodValidationException ex, HttpServletRequest request) {
        ProblemDetail problemDetail =
                createBaseProblemDetail(HttpStatus.BAD_REQUEST, "Validation Error", ex, request);

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

        if (!ex.getCrossParameterValidationResults().isEmpty()) {
            List<String> crossErrors =
                    ex.getCrossParameterValidationResults().stream()
                            .map(MessageSourceResolvable::getDefaultMessage)
                            .toList();
            problemDetail.setProperty("crossParameterErrors", crossErrors);
        }

        return ResponseEntity.status(ex.getStatusCode()).body(problemDetail);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        ProblemDetail problemDetail =
                createBaseProblemDetail(HttpStatus.BAD_REQUEST, "Invalid Path Variable", ex, request);
        problemDetail.setProperty("parameter", ex.getName());
        problemDetail.setProperty(
                "expectedType",
                ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "Unknown");
        problemDetail.setProperty("invalidValue", ex.getValue());
        return ResponseEntity.badRequest().body(problemDetail);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpectedException(
            Exception ex, HttpServletRequest request) {
        log.error("Unexpected error occurred", ex);
        ProblemDetail problemDetail = createBaseProblemDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", ex, request);
        // Never leak raw exception messages to clients
        problemDetail.setDetail("An unexpected internal error occurred");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problemDetail);
    }

    private ResponseEntity<ProblemDetail> buildErrorResponse(
            HttpStatus status, String title, Exception ex, HttpServletRequest request) {
        ProblemDetail problemDetail = createBaseProblemDetail(status, title, ex, request);
        if (status == HttpStatus.NOT_FOUND) {
            log.info("{} {} -> {} {}", request.getMethod(), request.getRequestURI(), status.value(), title);
        } else if (status.is4xxClientError()) {
            log.warn("{} {} -> {} {}", request.getMethod(), request.getRequestURI(), status.value(), title);
        } else if (status.is5xxServerError()) {
            log.error("{} {} -> {} {}", request.getMethod(), request.getRequestURI(), status.value(), title);
        }
        return ResponseEntity.status(status).body(problemDetail);
    }

    private ProblemDetail createBaseProblemDetail(
            HttpStatus status, String title, Exception ex, HttpServletRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatus(status);
        problemDetail.setTitle(title);
        problemDetail.setDetail(ex.getMessage());
        problemDetail.setType(URI.create(errorBaseUrl + status.value()));
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty(ERROR_CODE, title.toUpperCase().replace(" ", "_"));
        problemDetail.setProperty(TIMESTAMP, Instant.now());

        addDebugInfo(problemDetail, ex);
        addRequestMetadata(problemDetail, request);
        return problemDetail;
    }

    private void addDebugInfo(ProblemDetail detail, Exception ex) {
        if (isDevProfile) {
            detail.setProperty("exception", ex.getClass().getName());
            String fullStackTrace = ExceptionUtils.getStackTrace(ex);
            String truncatedStackTrace =
                    fullStackTrace.length() > MAX_STACK_TRACE_LENGTH
                            ? fullStackTrace.substring(0, MAX_STACK_TRACE_LENGTH) + "..."
                            : fullStackTrace;
            detail.setProperty("stackTrace", truncatedStackTrace);
        }
    }

    private void addRequestMetadata(ProblemDetail detail, HttpServletRequest request) {
        Map<String, String> metadata =
                Map.of(
                        "httpMethod", request.getMethod(),
                        "requestPath", request.getRequestURI(),
                        "userAgent", Optional.ofNullable(request.getHeader("User-Agent")).orElse(""),
                        "requestId", Optional.ofNullable(request.getHeader("X-Request-Id")).orElse(""),
                        "protocol", request.getProtocol(),
                        "scheme", request.getScheme(),
                        "isSecure", String.valueOf(request.isSecure()));
        detail.setProperty("request", metadata);
    }
}
