package com.example.boot4ref.common.rest;

import com.example.boot4ref.common.exception.BusinessRuleException;
import com.example.boot4ref.common.exception.ResourceConflictException;
import com.example.boot4ref.common.exception.ResourceNotFoundException;
import com.example.boot4ref.common.exception.ServiceUnavailableException;
import com.example.boot4ref.order.exception.InsufficientStockException;
import com.example.boot4ref.order.exception.OrderConflictException;
import com.example.boot4ref.order.exception.OrderNotFoundException;
import com.example.boot4ref.product.exception.ProductConflictException;
import com.example.boot4ref.product.exception.ProductNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import com.example.boot4ref.config.ApplicationProperties;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ExceptionTranslatorTest.TestController.class)
@Import({ExceptionTranslator.class, ExceptionTranslatorTest.TestController.class})
@EnableConfigurationProperties(ApplicationProperties.class)
class ExceptionTranslatorTest {

    @Autowired
    private MockMvc mockMvc;

    // -- Domain exception handlers --

    @Test
    void shouldReturn404WithProblemDetailWhenResourceNotFound() throws Exception {
        mockMvc.perform(get("/test/not-found")
                        .header("X-Request-Id", "req-123"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/resource-not-found"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Resource not found"))
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void shouldUseXRequestIdAsTraceIdFallback() throws Exception {
        mockMvc.perform(get("/test/not-found")
                        .header("X-Request-Id", "my-correlation-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.traceId").value("my-correlation-id"));
    }

    @Test
    void shouldGenerateUuidTraceIdWhenNoOtelOrRequestIdHeader() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.traceId").value(matchesPattern(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));
    }

    @Test
    void shouldReturn404WhenProductNotFoundExceptionThrown() throws Exception {
        mockMvc.perform(get("/test/product-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/product-not-found"))
                .andExpect(jsonPath("$.title").value("Product Not Found"))
                .andExpect(jsonPath("$.detail").value("Product not found with id: 42"))
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void shouldReturn404WhenOrderNotFoundExceptionThrown() throws Exception {
        mockMvc.perform(get("/test/order-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/order-not-found"))
                .andExpect(jsonPath("$.title").value("Order Not Found"))
                .andExpect(jsonPath("$.detail").value("Order not found with id: 99"))
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void shouldReturn409WithProblemDetailWhenResourceConflict() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/resource-conflict"))
                .andExpect(jsonPath("$.title").value("Resource Conflict"))
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_CONFLICT"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void shouldReturn409WhenProductConflictExceptionThrown() throws Exception {
        mockMvc.perform(get("/test/product-conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/product-conflict"))
                .andExpect(jsonPath("$.title").value("Product Conflict"))
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_CONFLICT"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void shouldReturn409WhenOrderConflictExceptionThrown() throws Exception {
        mockMvc.perform(get("/test/order-conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/order-conflict"))
                .andExpect(jsonPath("$.title").value("Order Conflict"))
                .andExpect(jsonPath("$.errorCode").value("ORDER_CONFLICT"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void shouldReturn503WhenServiceUnavailable() throws Exception {
        mockMvc.perform(get("/test/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/service-unavailable"))
                .andExpect(jsonPath("$.title").value("Service Unavailable"))
                .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(header().string("Retry-After", "30"));
    }

    // -- Spring validation handlers --

    @Test
    void shouldReturn400WithFieldErrorsWhenMethodArgumentNotValid() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"count\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/request-body-validation-error"))
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.errorCode").value("REQUEST_BODY_VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].field").exists())
                .andExpect(jsonPath("$.errors[0].objectName").exists())
                .andExpect(jsonPath("$.errors[0].rejectedValue").exists())
                .andExpect(jsonPath("$.errors[0].message").exists())
                .andExpect(jsonPath("$.errors[0].errorCode").exists())
                .andExpect(jsonPath("$.errors[0].bindingFailure").exists())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void shouldReturn400WhenConstraintViolation() throws Exception {
        mockMvc.perform(get("/test/constraint-violation"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/constraint-violation"))
                .andExpect(jsonPath("$.title").value("Constraint Violation"))
                .andExpect(jsonPath("$.errorCode").value("CONSTRAINT_VIOLATION"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void shouldReturn400WithValidationErrorsWhenHandlerMethodValidation() throws Exception {
        mockMvc.perform(get("/test/validated-param")
                        .param("id", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/parameter-validation-error"))
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.errorCode").value("PARAMETER_VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors").isArray())
                .andExpect(jsonPath("$.validationErrors[0].parameter").exists())
                .andExpect(jsonPath("$.validationErrors[0].type").exists())
                .andExpect(jsonPath("$.validationErrors[0].messages").isArray())
                .andExpect(jsonPath("$.traceId").exists());
    }

    // -- HTTP errors --

    @Test
    void shouldReturn405WhenMethodNotAllowed() throws Exception {
        mockMvc.perform(put("/test/not-found"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/method-not-allowed"))
                .andExpect(jsonPath("$.title").value("Method Not Allowed"))
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void shouldReturn415WhenMediaTypeNotSupported() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/unsupported-media-type"))
                .andExpect(jsonPath("$.title").value("Unsupported Media Type"))
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void shouldReturn406WhenMediaTypeNotAcceptable() throws Exception {
        mockMvc.perform(get("/test/json-response")
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    void shouldReturn400WhenMissingRequestParameter() throws Exception {
        mockMvc.perform(get("/test/required-param"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/missing-request-parameter"))
                .andExpect(jsonPath("$.title").value("Missing Request Parameter"))
                .andExpect(jsonPath("$.errorCode").value("MISSING_REQUEST_PARAMETER"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void shouldReturn404WhenNoResourceFound() throws Exception {
        mockMvc.perform(get("/nonexistent/path"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/no-resource-found"))
                .andExpect(jsonPath("$.title").value("No Resource Found"))
                .andExpect(jsonPath("$.errorCode").value("NO_RESOURCE_FOUND"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    // -- Other --

    @Test
    void shouldReturn400WhenTypeMismatch() throws Exception {
        mockMvc.perform(get("/test/type-mismatch/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/invalid-path-variable"))
                .andExpect(jsonPath("$.title").value("Invalid Path Variable"))
                .andExpect(jsonPath("$.errorCode").value("INVALID_PATH_VARIABLE"))
                .andExpect(jsonPath("$.parameter").value("id"))
                .andExpect(jsonPath("$.expectedType").value("Long"))
                .andExpect(jsonPath("$.invalidValue").value("abc"));
    }

    @Test
    void shouldReturn400WhenMalformedJson() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/malformed-json"))
                .andExpect(jsonPath("$.title").value("Malformed JSON"))
                .andExpect(jsonPath("$.detail").value("Malformed JSON request body"));
    }

    @Test
    void shouldReturn503WhenTransientDataAccessFailure() throws Exception {
        mockMvc.perform(get("/test/transient-failure"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/service-temporarily-unavailable"))
                .andExpect(jsonPath("$.title").value("Service Temporarily Unavailable"))
                .andExpect(jsonPath("$.detail").value("Database temporarily unavailable, please retry"))
                .andExpect(jsonPath("$.errorCode").value("SERVICE_TEMPORARILY_UNAVAILABLE"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(header().string("Retry-After", "30"));
    }

    @Test
    void shouldReturn409WhenDataIntegrityViolation() throws Exception {
        mockMvc.perform(get("/test/data-integrity-violation"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/data-integrity-violation"))
                .andExpect(jsonPath("$.title").value("Data Integrity Violation"))
                .andExpect(jsonPath("$.detail").value("Operation violates a data integrity constraint (e.g. referenced by other records)"))
                .andExpect(jsonPath("$.errorCode").value("DATA_INTEGRITY_VIOLATION"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void shouldReturn409Not503WhenConcurrencyFailure() throws Exception {
        // ConcurrencyFailureException extends TransientDataAccessException.
        // Verify ExceptionDepthComparator routes to 409, not 503.
        mockMvc.perform(get("/test/optimistic-lock-failure"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/concurrency-conflict"))
                .andExpect(jsonPath("$.title").value("Concurrency Conflict"))
                .andExpect(jsonPath("$.detail").value("Concurrent modification conflict, please retry"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void shouldReturn500ForUnexpectedException() throws Exception {
        mockMvc.perform(get("/test/error"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/internal-server-error"))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.detail").value("An unexpected internal error occurred"))
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(jsonPath("$.request").doesNotExist());
    }

    // -- Annotation-driven domain exception tests --

    @Test
    void shouldIncludeStructuredPropertiesForBusinessRuleException() throws Exception {
        mockMvc.perform(get("/test/insufficient-stock"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/insufficient-stock"))
                .andExpect(jsonPath("$.title").value("Insufficient Stock"))
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.productId").value(42))
                .andExpect(jsonPath("$.requested").value(100))
                .andExpect(jsonPath("$.available").value(5))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void shouldFallbackToDefaultsWhenNoProblemTypeAnnotation() throws Exception {
        mockMvc.perform(get("/test/business-rule-unannotated"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/business-rule-violation"))
                .andExpect(jsonPath("$.title").value("Business Rule Violation"))
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    // -- Test-only controller for triggering exceptions --

    @RestController
    static class TestController {

        @GetMapping("/test/not-found")
        public void notFound() {
            throw new ResourceNotFoundException("Resource not found");
        }

        @GetMapping("/test/product-not-found")
        public void productNotFound() {
            throw new ProductNotFoundException(42L);
        }

        @GetMapping("/test/order-not-found")
        public void orderNotFound() {
            throw new OrderNotFoundException(99L);
        }

        @GetMapping("/test/conflict")
        public void conflict() {
            throw new ResourceConflictException("Resource already exists");
        }

        @GetMapping("/test/product-conflict")
        public void productConflict() {
            throw new ProductConflictException("Product SKU already exists");
        }

        @GetMapping("/test/order-conflict")
        public void orderConflict() {
            throw new OrderConflictException("Order already confirmed");
        }

        @GetMapping("/test/unavailable")
        public void unavailable() {
            throw new ServiceUnavailableException("Downstream service is down");
        }

        @PostMapping("/test/validate")
        public void validate(@jakarta.validation.Valid @RequestBody TestRequest request) {
            // validation triggers MethodArgumentNotValidException
        }

        @GetMapping("/test/constraint-violation")
        public void constraintViolation() {
            @SuppressWarnings("unchecked")
            ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
            Path path = mock(Path.class);
            when(path.toString()).thenReturn("name");
            when(violation.getPropertyPath()).thenReturn(path);
            when(violation.getMessage()).thenReturn("must not be blank");
            throw new ConstraintViolationException("Validation failed", Set.of(violation));
        }

        @GetMapping("/test/validated-param")
        public String validatedParam(@RequestParam @Min(1) Long id) {
            return "ok:" + id;
        }

        @GetMapping("/test/type-mismatch/{id}")
        public String typeMismatch(@PathVariable Long id) {
            return "ok:" + id;
        }

        @GetMapping(value = "/test/json-response", produces = MediaType.APPLICATION_JSON_VALUE)
        public Map<String, String> jsonResponse() {
            return Map.of("status", "ok");
        }

        @GetMapping("/test/transient-failure")
        public void transientFailure() {
            throw new TransientDataAccessResourceException("Connection refused");
        }

        @GetMapping("/test/data-integrity-violation")
        public void dataIntegrityViolation() {
            throw new DataIntegrityViolationException("Unique constraint violated");
        }

        @GetMapping("/test/optimistic-lock-failure")
        public void optimisticLockFailure() {
            throw new ObjectOptimisticLockingFailureException("Product", 42L);
        }

        @GetMapping("/test/required-param")
        public String requiredParam(@RequestParam String name) {
            return "ok:" + name;
        }

        @GetMapping("/test/error")
        public void error() {
            throw new RuntimeException("Something went wrong");
        }

        @GetMapping("/test/insufficient-stock")
        public void insufficientStock() {
            throw new InsufficientStockException(42L, 100, 5);
        }

        @GetMapping("/test/business-rule-unannotated")
        public void businessRuleUnannotated() {
            // Anonymous subclass without its own @ProblemType — tests @Inherited fallback
            throw new BusinessRuleException("Custom rule violated") {};
        }

        record TestRequest(@NotBlank String name, @Positive Integer count) {}
    }
}
