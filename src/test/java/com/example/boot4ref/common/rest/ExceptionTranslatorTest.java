package com.example.boot4ref.common.rest;

import com.example.boot4ref.common.exception.ResourceConflictException;
import com.example.boot4ref.common.exception.ResourceNotFoundException;
import com.example.boot4ref.common.exception.ServiceUnavailableException;
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
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ExceptionTranslatorTest.TestController.class)
@Import({ExceptionTranslator.class, ExceptionTranslatorTest.TestController.class})
class ExceptionTranslatorTest {

    @Autowired
    private MockMvc mockMvc;

    // -- Domain exception handlers --

    @Test
    void shouldReturn404WithProblemDetailWhenResourceNotFound() throws Exception {
        mockMvc.perform(get("/test/not-found")
                        .header("User-Agent", "JUnit")
                        .header("X-Request-Id", "req-123"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/404"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Resource not found"))
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.request.httpMethod").value("GET"))
                .andExpect(jsonPath("$.request.requestPath").value("/test/not-found"))
                .andExpect(jsonPath("$.request.userAgent").value("JUnit"))
                .andExpect(jsonPath("$.request.requestId").value("req-123"))
                .andExpect(jsonPath("$.request.protocol").exists())
                .andExpect(jsonPath("$.request.scheme").exists())
                .andExpect(jsonPath("$.request.isSecure").exists());
    }

    @Test
    void shouldReturn404WhenProductNotFoundExceptionThrown() throws Exception {
        mockMvc.perform(get("/test/product-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/404"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.detail").value("Product not found with id: 42"))
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.request").exists());
    }

    @Test
    void shouldReturn404WhenOrderNotFoundExceptionThrown() throws Exception {
        mockMvc.perform(get("/test/order-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/404"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.detail").value("Order not found with id: 99"))
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void shouldReturn409WithProblemDetailWhenResourceConflict() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/409"))
                .andExpect(jsonPath("$.title").value("Resource Conflict"))
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_CONFLICT"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.request").exists());
    }

    @Test
    void shouldReturn409WhenProductConflictExceptionThrown() throws Exception {
        mockMvc.perform(get("/test/product-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/409"))
                .andExpect(jsonPath("$.title").value("Resource Conflict"))
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_CONFLICT"));
    }

    @Test
    void shouldReturn409WhenOrderConflictExceptionThrown() throws Exception {
        mockMvc.perform(get("/test/order-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/409"))
                .andExpect(jsonPath("$.title").value("Resource Conflict"))
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_CONFLICT"));
    }

    @Test
    void shouldReturn503WhenServiceUnavailable() throws Exception {
        mockMvc.perform(get("/test/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/503"))
                .andExpect(jsonPath("$.title").value("Service Unavailable"))
                .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.request").exists());
    }

    // -- Spring validation handlers --

    @Test
    void shouldReturn400WithFieldErrorsWhenMethodArgumentNotValid() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"count\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/400"))
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].field").exists())
                .andExpect(jsonPath("$.errors[0].objectName").exists())
                .andExpect(jsonPath("$.errors[0].rejectedValue").exists())
                .andExpect(jsonPath("$.errors[0].message").exists())
                .andExpect(jsonPath("$.errors[0].errorCode").exists())
                .andExpect(jsonPath("$.errors[0].bindingFailure").exists());
    }

    @Test
    void shouldReturn400WhenConstraintViolation() throws Exception {
        mockMvc.perform(get("/test/constraint-violation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/400"))
                .andExpect(jsonPath("$.title").value("Constraint Violation"))
                .andExpect(jsonPath("$.errorCode").value("CONSTRAINT_VIOLATION"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void shouldReturn400WithValidationErrorsWhenHandlerMethodValidation() throws Exception {
        mockMvc.perform(get("/test/validated-param")
                        .param("id", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/400"))
                .andExpect(jsonPath("$.title").value("Method Validation Error"))
                .andExpect(jsonPath("$.validationErrors").isArray())
                .andExpect(jsonPath("$.validationErrors[0].parameter").exists())
                .andExpect(jsonPath("$.validationErrors[0].type").exists())
                .andExpect(jsonPath("$.validationErrors[0].messages").isArray());
    }

    // -- HTTP errors --

    @Test
    void shouldReturn405WhenMethodNotAllowed() throws Exception {
        mockMvc.perform(put("/test/not-found"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void shouldReturn415WhenMediaTypeNotSupported() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not json"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void shouldReturn406WhenMediaTypeNotAcceptable() throws Exception {
        mockMvc.perform(get("/test/json-response")
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable());
    }

    // -- Other --

    @Test
    void shouldReturn400WhenTypeMismatch() throws Exception {
        mockMvc.perform(get("/test/type-mismatch/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/400"))
                .andExpect(jsonPath("$.title").value("Invalid Path Variable"))
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
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/400"))
                .andExpect(jsonPath("$.title").value("Malformed JSON"))
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void shouldReturn500ForUnexpectedException() throws Exception {
        mockMvc.perform(get("/test/error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/500"))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.detail").value("An unexpected internal error occurred"))
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.timestamp").exists());
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

        @GetMapping("/test/error")
        public void error() {
            throw new RuntimeException("Something went wrong");
        }

        record TestRequest(@NotBlank String name, @Positive Integer count) {}
    }
}
