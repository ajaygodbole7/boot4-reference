package com.example.boot4ref.product.rest;

import com.example.boot4ref.common.rest.ExceptionTranslator;
import com.example.boot4ref.config.ApplicationProperties;
import com.example.boot4ref.product.ProductStatus;
import com.example.boot4ref.product.exception.ProductConflictException;
import com.example.boot4ref.product.exception.ProductNotFoundException;
import com.example.boot4ref.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller tests for {@link ProductController} covering full CRUD, lifecycle transitions,
 * filtering, keyset pagination, PATCH (partial update), and optimistic lock 409.
 *
 * <p>Uses {@code @WebMvcTest} (Boot 4) with {@code @MockitoBean} (replaces deprecated @MockBean).
 */
@WebMvcTest(ProductController.class)
@Import(ExceptionTranslator.class)
@EnableConfigurationProperties(ApplicationProperties.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    // =========== Fixtures ===========

    private static ProductResponse sampleResponse(Long id) {
        return new ProductResponse(
                id, "Test Widget", "A test product",
                new BigDecimal("19.99"), 10, "DRAFT",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z")
        );
    }

    private static ProductResponse activeResponse(Long id) {
        return new ProductResponse(
                id, "Active Widget", "An active product",
                new BigDecimal("29.99"), 5, "ACTIVE",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z")
        );
    }

    // =========== Bean Validation tests (existing 7, preserved) ===========

    @Test
    void shouldReturn400WithFieldErrorsWhenNameIsBlank() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","description":"A product","price":10.00,"stock":5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/400"))
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')].rejectedValue").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')].message").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')].errorCode").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')].bindingFailure").exists());
    }

    @Test
    void shouldReturn400WithFieldErrorsWhenPriceIsNegative() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Valid Name","description":"desc","price":-5.00,"stock":5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/400"))
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[?(@.field == 'price')]").exists());
    }

    @Test
    void shouldReturn400WithFieldErrorsWhenNameTooLong() throws Exception {
        String longName = "A".repeat(101);
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"desc","price":10.00,"stock":5}
                                """.formatted(longName)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/400"))
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists());
    }

    @Test
    void shouldReturn400WithMultipleFieldErrors() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","description":"desc","price":-1.00,"stock":5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/400"))
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    void shouldReturn400WhenRequestBodyIsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/400"))
                .andExpect(jsonPath("$.title").value("Malformed JSON"))
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void shouldReturn415WhenContentTypeIsNotJson() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not json"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void shouldReturn405WhenUsingWrongHttpMethod() throws Exception {
        mockMvc.perform(delete("/api/products"))
                .andExpect(status().isMethodNotAllowed());
    }

    // =========== POST /api/products ===========

    @Test
    void shouldReturn201WithLocationWhenProductCreated() throws Exception {
        when(productService.create(any(ProductCreateRequest.class))).thenReturn(sampleResponse(42L));

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Test Widget","description":"A test product","price":19.99,"stock":10}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/products/42"))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.name").value("Test Widget"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void shouldReturn400WhenCreateRequestIsInvalid() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","price":null,"stock":-1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }

    @Test
    void shouldReturn400WhenCreatePriceExceedsDigitsPrecision() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Widget","description":"desc","price":12345678901234567.99,"stock":5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.errors[?(@.field == 'price')]").exists());
    }

    @Test
    void shouldReturn400WhenPatchPriceExceedsDigitsPrecision() throws Exception {
        mockMvc.perform(patch("/api/products/42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":1.123456}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.errors[?(@.field == 'price')]").exists());
    }

    // =========== GET /api/products ===========

    @Test
    void shouldReturn200WithProductListWhenListProducts() throws Exception {
        when(productService.listFiltered(null, null, null, null, null))
                .thenReturn(List.of(sampleResponse(1L), sampleResponse(2L)));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[1].id").value(2));
    }

    @Test
    void shouldReturn200WithEmptyListWhenNoProducts() throws Exception {
        when(productService.listFiltered(null, null, null, null, null))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // =========== GET /api/products with filters ===========

    @Test
    void shouldReturn200WithFilteredProductsWhenStatusAndPriceProvided() throws Exception {
        when(productService.listFiltered(
                eq(ProductStatus.ACTIVE),
                eq(new BigDecimal("10.00")),
                eq(new BigDecimal("50.00")),
                eq(null),
                eq(20)
        )).thenReturn(List.of(activeResponse(1L)));

        mockMvc.perform(get("/api/products")
                        .param("status", "ACTIVE")
                        .param("minPrice", "10.00")
                        .param("maxPrice", "50.00")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void shouldReturn200WithNextPageUsingKeysetCursor() throws Exception {
        when(productService.listFiltered(
                eq(null),
                eq(null),
                eq(null),
                eq(1L),
                eq(10)
        )).thenReturn(List.of(sampleResponse(2L)));

        mockMvc.perform(get("/api/products")
                        .param("afterId", "1")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // =========== GET /api/products/{id} ===========

    @Test
    void shouldReturn200WithProductWhenFound() throws Exception {
        when(productService.findById(42L)).thenReturn(sampleResponse(42L));

        mockMvc.perform(get("/api/products/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.name").value("Test Widget"));
    }

    @Test
    void shouldReturn404WhenProductNotFound() throws Exception {
        when(productService.findById(99L)).thenThrow(new ProductNotFoundException(99L));

        mockMvc.perform(get("/api/products/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/404"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"));
    }

    // =========== PUT /api/products/{id} ===========

    @Test
    void shouldReturn200WithUpdatedProductWhenPutSucceeds() throws Exception {
        when(productService.update(eq(42L), any(ProductUpdateRequest.class))).thenReturn(sampleResponse(42L));

        mockMvc.perform(put("/api/products/42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Updated Widget","description":"Updated","price":29.99,"stock":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42));
    }

    @Test
    void shouldReturn404WhenUpdateProductNotFound() throws Exception {
        when(productService.update(eq(99L), any(ProductUpdateRequest.class)))
                .thenThrow(new ProductNotFoundException(99L));

        mockMvc.perform(put("/api/products/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Widget","price":9.99,"stock":0}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"));
    }

    @Test
    void shouldReturn400WhenUpdateRequestIsInvalid() throws Exception {
        mockMvc.perform(put("/api/products/42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","price":-1,"stock":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }

    @Test
    void shouldReturn409WhenPutHasStaleVersion() throws Exception {
        when(productService.update(eq(42L), any(ProductUpdateRequest.class)))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
                        "Product", 42L));

        mockMvc.perform(put("/api/products/42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Widget","description":"desc","price":9.99,"stock":5}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/409"))
                .andExpect(jsonPath("$.title").value("Optimistic Lock Conflict"))
                .andExpect(jsonPath("$.detail").value("Resource was modified by another request"));
    }

    // =========== PATCH /api/products/{id} ===========

    @Test
    void shouldReturn200WithPatchedProductWhenPartialUpdate() throws Exception {
        ProductResponse patched = new ProductResponse(
                42L, "Patched Widget", "A test product",
                new BigDecimal("19.99"), 10, "DRAFT",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z")
        );
        when(productService.patch(eq(42L), any(ProductPatchRequest.class))).thenReturn(patched);

        mockMvc.perform(patch("/api/products/42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Patched Widget"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Patched Widget"));
    }

    @Test
    void shouldReturn200WhenPatchUpdatesOnlyPrice() throws Exception {
        ProductResponse patched = new ProductResponse(
                42L, "Test Widget", "A test product",
                new BigDecimal("99.99"), 10, "DRAFT",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z")
        );
        when(productService.patch(eq(42L), any(ProductPatchRequest.class))).thenReturn(patched);

        mockMvc.perform(patch("/api/products/42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":99.99}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(99.99));
    }

    @Test
    void shouldReturn404WhenPatchProductNotFound() throws Exception {
        when(productService.patch(eq(99L), any(ProductPatchRequest.class)))
                .thenThrow(new ProductNotFoundException(99L));

        mockMvc.perform(patch("/api/products/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Updated"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"));
    }

    @Test
    void shouldReturn409WhenPatchHasStaleVersion() throws Exception {
        when(productService.patch(eq(42L), any(ProductPatchRequest.class)))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
                        "Product", 42L));

        mockMvc.perform(patch("/api/products/42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"New Name"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/409"))
                .andExpect(jsonPath("$.title").value("Optimistic Lock Conflict"))
                .andExpect(jsonPath("$.detail").value("Resource was modified by another request"));
    }

    // =========== DELETE /api/products/{id} ===========

    @Test
    void shouldReturn204WhenProductDeleted() throws Exception {
        doNothing().when(productService).delete(42L);

        mockMvc.perform(delete("/api/products/42"))
                .andExpect(status().isNoContent());
    }

    @Test
    void shouldReturn404WhenDeleteProductNotFound() throws Exception {
        doThrow(new ProductNotFoundException(99L)).when(productService).delete(99L);

        mockMvc.perform(delete("/api/products/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"));
    }

    @Test
    void shouldReturn409WhenDeletingActiveProduct() throws Exception {
        doThrow(new ProductConflictException("Cannot delete product 42 in ACTIVE status"))
                .when(productService).delete(42L);

        mockMvc.perform(delete("/api/products/42"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/409"))
                .andExpect(jsonPath("$.title").value("Resource Conflict"));
    }

    // =========== Status transition: POST /api/products/{id}/status ===========

    @Test
    void shouldReturn200WhenValidStatusTransition() throws Exception {
        ProductResponse active = new ProductResponse(
                42L, "Test Widget", null,
                new BigDecimal("19.99"), 10, "ACTIVE",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z")
        );
        when(productService.transition(42L, ProductStatus.ACTIVE)).thenReturn(active);

        mockMvc.perform(post("/api/products/42/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ACTIVE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void shouldReturn409WhenInvalidStatusTransition() throws Exception {
        when(productService.transition(42L, ProductStatus.DRAFT))
                .thenThrow(new ProductConflictException(
                        "Invalid status transition from ACTIVE to DRAFT"));

        mockMvc.perform(post("/api/products/42/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DRAFT"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/409"))
                .andExpect(jsonPath("$.title").value("Resource Conflict"));
    }

    @Test
    void shouldReturn404WhenTransitionProductNotFound() throws Exception {
        when(productService.transition(99L, ProductStatus.ACTIVE))
                .thenThrow(new ProductNotFoundException(99L));

        mockMvc.perform(post("/api/products/99/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ACTIVE"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"));
    }
}
