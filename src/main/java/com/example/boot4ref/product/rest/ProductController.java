package com.example.boot4ref.product.rest;

import com.example.boot4ref.product.ProductStatus;
import com.example.boot4ref.product.service.ProductService;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Product REST controller. Delegates all logic to {@link ProductService}.
 *
 * <p>No Lombok -- manual Logger per CLAUDE.md (Lombok restricted to @Entity classes).
 * OpenAPI annotations live on {@link ProductApi} -- this class stays focused on HTTP mapping.
 */
@RestController
public class ProductController implements ProductApi {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @Override
    public ResponseEntity<List<ProductResponse>> listProducts(
            @Nullable ProductStatus status,
            @Nullable BigDecimal minPrice,
            @Nullable BigDecimal maxPrice,
            @Nullable Long afterId,
            @Nullable Integer limit) {
        return ResponseEntity.ok(
                productService.listFiltered(status, minPrice, maxPrice, afterId, limit));
    }

    @Override
    public ResponseEntity<ProductResponse> getProduct(Long id) {
        return ResponseEntity.ok(productService.findById(id));
    }

    @Override
    public ResponseEntity<ProductResponse> createProduct(ProductCreateRequest request) {
        ProductResponse created = productService.create(request);
        URI location = URI.create("/api/products/" + created.id());
        return ResponseEntity.created(location).body(created);
    }

    @Override
    public ResponseEntity<ProductResponse> updateProduct(Long id, ProductUpdateRequest request) {
        return ResponseEntity.ok(productService.update(id, request));
    }

    @Override
    public ResponseEntity<ProductResponse> patchProduct(Long id, ProductPatchRequest request) {
        return ResponseEntity.ok(productService.patch(id, request));
    }

    @Override
    public ResponseEntity<Void> deleteProduct(Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ProductResponse> transitionStatus(Long id, ProductStatusRequest request) {
        return ResponseEntity.ok(productService.transition(id, request.status()));
    }
}
