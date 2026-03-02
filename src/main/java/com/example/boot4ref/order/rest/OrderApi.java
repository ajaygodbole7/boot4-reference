package com.example.boot4ref.order.rest;

import com.example.boot4ref.order.OrderStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Order API contract. OpenAPI annotations live on the interface;
 * {@link OrderController} implements the business logic.
 */
@Tag(name = "Orders", description = "Order management API")
public interface OrderApi {

    @Operation(summary = "Create a new order")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order created (or existing order returned if idempotency key matches)"),
            @ApiResponse(responseCode = "400", description = "Validation error or missing idempotency key"),
            @ApiResponse(responseCode = "404", description = "Product not found"),
            @ApiResponse(responseCode = "422", description = "Business rule violation (non-ACTIVE product, insufficient stock, duplicate product IDs)")
    })
    @PostMapping("/api/orders")
    @NonNull ResponseEntity<OrderResponse> createOrder(
            @Parameter(description = "Idempotency key for at-most-once creation")
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody @NonNull OrderCreateRequest request);

    @Operation(summary = "Get order by ID with nested line items")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order found"),
            @ApiResponse(responseCode = "404", description = "Order not found")
    })
    @GetMapping("/api/orders/{id}")
    @NonNull ResponseEntity<OrderResponse> getOrder(
            @Parameter(description = "Order ID") @PathVariable @NonNull Long id);

    @Operation(summary = "List orders with optional filtering and keyset pagination")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Orders retrieved")
    })
    @GetMapping("/api/orders")
    @NonNull ResponseEntity<List<OrderResponse>> listOrders(
            @Parameter(description = "Filter by status") @RequestParam(required = false) @Nullable OrderStatus status,
            @Parameter(description = "Keyset cursor: id of last seen order") @RequestParam(required = false) @Nullable Long afterId,
            @Parameter(description = "Maximum results to return (default 20)") @RequestParam(required = false) @Nullable Integer limit);

    @Operation(summary = "Transition order status")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status transitioned"),
            @ApiResponse(responseCode = "404", description = "Order not found"),
            @ApiResponse(responseCode = "409", description = "Invalid status transition")
    })
    @PostMapping("/api/orders/{id}/status")
    @NonNull ResponseEntity<OrderResponse> transitionStatus(
            @Parameter(description = "Order ID") @PathVariable @NonNull Long id,
            @Valid @RequestBody @NonNull OrderStatusRequest request);
}
