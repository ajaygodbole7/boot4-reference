package com.example.boot4ref.order.rest;

import com.example.boot4ref.common.rest.ExceptionTranslator;
import com.example.boot4ref.config.ApplicationProperties;
import com.example.boot4ref.order.OrderStatus;
import com.example.boot4ref.order.exception.DiscontinuedProductException;
import com.example.boot4ref.order.exception.InsufficientStockException;
import com.example.boot4ref.order.exception.OrderConflictException;
import com.example.boot4ref.order.exception.OrderNotFoundException;
import com.example.boot4ref.order.service.OrderService;
import com.example.boot4ref.product.exception.ProductNotFoundException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller tests for {@link OrderController} covering order creation, retrieval,
 * listing, lifecycle transitions, and all error scenarios.
 *
 * <p>Uses {@code @WebMvcTest} (Boot 4) with {@code @MockitoBean} (replaces deprecated @MockBean).
 */
@WebMvcTest(controllers = OrderController.class)
@Import(ExceptionTranslator.class)
@EnableConfigurationProperties(ApplicationProperties.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    // =========== Fixtures ===========

    private static final Instant TIMESTAMP = Instant.parse("2026-01-01T00:00:00Z");

    private static OrderResponse sampleOrderResponse(Long id) {
        return new OrderResponse(
                id, "PLACED", new BigDecimal("39.98"), "key-123",
                List.of(new OrderLineResponse(100L, 1L, "Widget", 2, new BigDecimal("19.99"))),
                TIMESTAMP, TIMESTAMP
        );
    }

    private static OrderResponse confirmedOrderResponse(Long id) {
        return new OrderResponse(
                id, "CONFIRMED", new BigDecimal("39.98"), "key-456",
                List.of(new OrderLineResponse(100L, 1L, "Widget", 2, new BigDecimal("19.99"))),
                TIMESTAMP, TIMESTAMP
        );
    }

    // =========== POST /api/orders ===========

    @Test
    void shouldReturn201WithLocationHeaderWhenCreatingValidOrder() throws Exception {
        when(orderService.create(any(OrderCreateRequest.class), eq("key-123")))
                .thenReturn(sampleOrderResponse(42L));

        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":1,"quantity":2}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/orders/42"))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.totalAmount").value(39.98))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productName").value("Widget"));
    }

    @Test
    void shouldReturn201WhenCreatingOrderWithoutIdempotencyKey() throws Exception {
        when(orderService.create(any(OrderCreateRequest.class), isNull()))
                .thenReturn(sampleOrderResponse(43L));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":1,"quantity":2}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"));
    }

    @Test
    void shouldReturn400WhenOrderHasNoItems() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }

    @Test
    void shouldReturn400WhenOrderItemHasNullProductId() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":null,"quantity":2}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }

    @Test
    void shouldReturn400WhenOrderItemHasZeroQuantity() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":1,"quantity":0}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }

    @Test
    void shouldReturn400WhenOrderItemHasNegativeQuantity() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":1,"quantity":-1}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }

    @Test
    void shouldReturn400WhenRequestBodyIsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/400"))
                .andExpect(jsonPath("$.title").value("Malformed JSON"));
    }

    @Test
    void shouldReturn415WhenContentTypeIsNotJson() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not json"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void shouldReturn404WhenProductNotFoundDuringOrderCreation() throws Exception {
        when(orderService.create(any(OrderCreateRequest.class), isNull()))
                .thenThrow(new ProductNotFoundException(999L));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":999,"quantity":1}]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/404"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"));
    }

    // =========== GET /api/orders/{id} ===========

    @Test
    void shouldReturn200WithOrderAndNestedItemsWhenFound() throws Exception {
        when(orderService.findById(42L)).thenReturn(sampleOrderResponse(42L));

        mockMvc.perform(get("/api/orders/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.totalAmount").value(39.98))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].productId").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").value(19.99));
    }

    @Test
    void shouldReturn404WhenOrderNotFound() throws Exception {
        when(orderService.findById(99L)).thenThrow(new OrderNotFoundException(99L));

        mockMvc.perform(get("/api/orders/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/404"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"));
    }

    // =========== GET /api/orders ===========

    @Test
    void shouldReturn200WithOrderListWhenListOrders() throws Exception {
        when(orderService.listFiltered(null, null, null))
                .thenReturn(List.of(sampleOrderResponse(1L), sampleOrderResponse(2L)));

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[1].id").value(2));
    }

    @Test
    void shouldReturn200WithEmptyListWhenNoOrders() throws Exception {
        when(orderService.listFiltered(null, null, null))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // =========== POST /api/orders/{id}/status ===========

    @Test
    void shouldReturn200WhenValidStatusTransitionFromPlacedToConfirmed() throws Exception {
        when(orderService.transition(42L, OrderStatus.CONFIRMED))
                .thenReturn(confirmedOrderResponse(42L));

        mockMvc.perform(post("/api/orders/42/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"CONFIRMED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void shouldReturn409WhenInvalidStatusTransition() throws Exception {
        when(orderService.transition(42L, OrderStatus.SHIPPED))
                .thenThrow(new OrderConflictException(
                        "Invalid status transition from PLACED to SHIPPED"));

        mockMvc.perform(post("/api/orders/42/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SHIPPED"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/409"))
                .andExpect(jsonPath("$.title").value("Resource Conflict"));
    }

    @Test
    void shouldReturn404WhenTransitionOrderNotFound() throws Exception {
        when(orderService.transition(99L, OrderStatus.CONFIRMED))
                .thenThrow(new OrderNotFoundException(99L));

        mockMvc.perform(post("/api/orders/99/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"CONFIRMED"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"));
    }

    @Test
    void shouldReturn400WhenStatusRequestIsMissingStatus() throws Exception {
        mockMvc.perform(post("/api/orders/42/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }

    @Test
    void shouldReturn405WhenUsingDeleteOnOrders() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/orders"))
                .andExpect(status().isMethodNotAllowed());
    }

    // =========== Business rule violations ===========

    @Test
    void shouldReturn422WhenInsufficientStockForOrderItem() throws Exception {
        when(orderService.create(any(OrderCreateRequest.class), isNull()))
                .thenThrow(new InsufficientStockException(1L, 100, 5));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":1,"quantity":100}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/422"))
                .andExpect(jsonPath("$.title").value("Business Rule Violation"))
                .andExpect(jsonPath("$.detail").value(
                        "Insufficient stock for product 1: requested 100, available 5"));
    }

    @Test
    void shouldReturn422WhenOrderingDiscontinuedProduct() throws Exception {
        when(orderService.create(any(OrderCreateRequest.class), isNull()))
                .thenThrow(new DiscontinuedProductException(1L));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":1,"quantity":1}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://api.boot4ref.example.com/errors/422"))
                .andExpect(jsonPath("$.title").value("Business Rule Violation"))
                .andExpect(jsonPath("$.detail").value(
                        "Product 1 is discontinued and cannot be ordered"));
    }

    // =========== Filtering ===========

    @Test
    void shouldReturn200WithFilteredOrdersWhenStatusProvided() throws Exception {
        when(orderService.listFiltered(eq(OrderStatus.PLACED), isNull(), eq(20)))
                .thenReturn(List.of(sampleOrderResponse(1L)));

        mockMvc.perform(get("/api/orders")
                        .param("status", "PLACED")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("PLACED"));
    }

    @Test
    void shouldReturn200WithKeysetPaginatedOrders() throws Exception {
        when(orderService.listFiltered(isNull(), eq(1L), eq(10)))
                .thenReturn(List.of(sampleOrderResponse(2L)));

        mockMvc.perform(get("/api/orders")
                        .param("afterId", "1")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // =========== Idempotency constraint narrowing (P0-C) ===========

    @Test
    void shouldReturn201WhenIdempotencyKeyRaceWithCorrectConstraint() throws Exception {
        var cause = new ConstraintViolationException("duplicate key", null, "orders_idempotency_key_key");
        when(orderService.create(any(OrderCreateRequest.class), eq("dup-key")))
                .thenThrow(new DataIntegrityViolationException("constraint", cause));
        when(orderService.findByIdempotencyKey("dup-key")).thenReturn(sampleOrderResponse(42L));

        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "dup-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":1,"quantity":2}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(42));
    }

    @Test
    void shouldReturn500WhenNonIdempotencyConstraintViolation() throws Exception {
        var cause = new ConstraintViolationException("fk violation", null, "order_lines_product_id_fkey");
        when(orderService.create(any(OrderCreateRequest.class), eq("some-key")))
                .thenThrow(new DataIntegrityViolationException("constraint", cause));

        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "some-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":1,"quantity":2}]}
                                """))
                .andExpect(status().isInternalServerError());
    }
}
