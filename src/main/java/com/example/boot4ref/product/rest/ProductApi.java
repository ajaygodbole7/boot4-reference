package com.example.boot4ref.product.rest;

import com.example.boot4ref.product.ProductStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Product API contract. OpenAPI annotations live on the interface;
 * {@link ProductController} implements the business logic.
 */
@Tag(name = "Products", description = "Product management API")
public interface ProductApi {

    @Operation(summary = "List products with optional filtering and keyset pagination")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Products retrieved")
    })
    @GetMapping("/api/products")
    @NonNull ResponseEntity<List<ProductResponse>> listProducts(
            @Parameter(description = "Filter by status") @RequestParam(required = false) @Nullable ProductStatus status,
            @Parameter(description = "Minimum price (inclusive)") @RequestParam(required = false) @Nullable BigDecimal minPrice,
            @Parameter(description = "Maximum price (inclusive)") @RequestParam(required = false) @Nullable BigDecimal maxPrice,
            @Parameter(description = "Keyset cursor: id of last seen product") @RequestParam(required = false) @Nullable Long afterId,
            @Parameter(description = "Maximum results to return (default 20)") @RequestParam(required = false) @Nullable Integer limit);

    @Operation(summary = "Get product by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Product found"),
            @ApiResponse(responseCode = "404", description = "Product not found")
    })
    @GetMapping("/api/products/{id}")
    @NonNull ResponseEntity<ProductResponse> getProduct(
            @Parameter(description = "Product ID") @PathVariable @NonNull Long id);

    @Operation(summary = "Create a new product")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Product created"),
            @ApiResponse(responseCode = "400", description = "Validation error")
    })
    @PostMapping("/api/products")
    @NonNull ResponseEntity<ProductResponse> createProduct(
            @Valid @RequestBody @NonNull ProductCreateRequest request);

    @Operation(summary = "Update an existing product (full replacement)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Product updated"),
            @ApiResponse(responseCode = "404", description = "Product not found"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "409", description = "Optimistic lock conflict (stale version)")
    })
    @PutMapping("/api/products/{id}")
    @NonNull ResponseEntity<ProductResponse> updateProduct(
            @Parameter(description = "Product ID") @PathVariable @NonNull Long id,
            @Valid @RequestBody @NonNull ProductUpdateRequest request);

    @Operation(summary = "Partially update a product (non-null fields applied)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Product patched"),
            @ApiResponse(responseCode = "404", description = "Product not found"),
            @ApiResponse(responseCode = "409", description = "Optimistic lock conflict (stale version)")
    })
    @PatchMapping("/api/products/{id}")
    @NonNull ResponseEntity<ProductResponse> patchProduct(
            @Parameter(description = "Product ID") @PathVariable @NonNull Long id,
            @Valid @RequestBody @NonNull ProductPatchRequest request);

    @Operation(summary = "Delete a product")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Product deleted"),
            @ApiResponse(responseCode = "404", description = "Product not found")
    })
    @DeleteMapping("/api/products/{id}")
    @NonNull ResponseEntity<Void> deleteProduct(
            @Parameter(description = "Product ID") @PathVariable @NonNull Long id);

    @Operation(summary = "Transition product status")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status transitioned"),
            @ApiResponse(responseCode = "404", description = "Product not found"),
            @ApiResponse(responseCode = "409", description = "Invalid status transition")
    })
    @PostMapping("/api/products/{id}/status")
    @NonNull ResponseEntity<ProductResponse> transitionStatus(
            @Parameter(description = "Product ID") @PathVariable @NonNull Long id,
            @Valid @RequestBody @NonNull ProductStatusRequest request);
}
