package com.example.boot4ref.product.repository;

import com.example.boot4ref.AbstractIntegrationTest;
import com.example.boot4ref.product.Product;
import com.example.boot4ref.product.ProductStatus;
import com.example.boot4ref.product.specification.ProductSpecifications;
import net.ttddyy.dsproxy.QueryCountHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ProductRepository.
 * Runs against Testcontainers PostgreSQL via AbstractIntegrationTest.
 * datasource-proxy assertions verify no N+1 queries occur.
 */
class ProductRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void resetQueryCount() {
        QueryCountHolder.clear();
    }

    @Test
    void shouldPersistAndRetrieveProduct() {
        Product product = Product.builder()
                .name("Test Widget")
                .description("A test product")
                .price(new BigDecimal("19.99"))
                .stock(10)
                .status(ProductStatus.DRAFT)
                .build();

        Product saved = productRepository.save(product);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getVersion()).isNotNull();

        Product found = productRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("Test Widget");
        assertThat(found.getDescription()).isEqualTo("A test product");
        assertThat(found.getPrice()).isEqualByComparingTo(new BigDecimal("19.99"));
        assertThat(found.getStock()).isEqualTo(10);
        assertThat(found.getStatus()).isEqualTo(ProductStatus.DRAFT);
    }

    @Test
    void shouldFindAllProducts() {
        Product p1 = Product.builder()
                .name("Widget One")
                .price(new BigDecimal("9.99"))
                .stock(5)
                .status(ProductStatus.DRAFT)
                .build();
        Product p2 = Product.builder()
                .name("Widget Two")
                .price(new BigDecimal("29.99"))
                .stock(0)
                .status(ProductStatus.ACTIVE)
                .build();
        productRepository.saveAll(List.of(p1, p2));

        List<Product> all = productRepository.findAll();
        assertThat(all).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldDeleteProduct() {
        Product product = Product.builder()
                .name("Deletable Widget")
                .price(new BigDecimal("5.00"))
                .stock(1)
                .status(ProductStatus.DRAFT)
                .build();

        Product saved = productRepository.save(product);
        assertThat(productRepository.findById(saved.getId())).isPresent();

        productRepository.deleteById(saved.getId());
        assertThat(productRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    void shouldUpdateProductStatus() {
        Product product = Product.builder()
                .name("Status Widget")
                .price(new BigDecimal("15.00"))
                .stock(3)
                .status(ProductStatus.DRAFT)
                .build();

        Product saved = productRepository.save(product);
        saved.setStatus(ProductStatus.ACTIVE);
        Product updated = productRepository.save(saved);

        assertThat(updated.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(updated.getVersion()).isGreaterThan(0L);
    }

    @Test
    void productEquality_basedOnId() {
        Product p1 = Product.builder()
                .name("Equality Widget")
                .price(new BigDecimal("1.00"))
                .stock(0)
                .status(ProductStatus.DRAFT)
                .build();
        Product saved = productRepository.save(p1);

        Product found = productRepository.findById(saved.getId()).orElseThrow();
        assertThat(saved).isEqualTo(found);
        assertThat(saved.hashCode()).isEqualTo(found.hashCode());
    }

    // =========== Filter + keyset pagination tests ===========

    @Test
    void shouldFilterByStatus() {
        Product draft = Product.builder()
                .name("Draft Filter Widget")
                .price(new BigDecimal("10.00"))
                .stock(1)
                .status(ProductStatus.DRAFT)
                .build();
        Product active = Product.builder()
                .name("Active Filter Widget")
                .price(new BigDecimal("20.00"))
                .stock(2)
                .status(ProductStatus.ACTIVE)
                .build();
        productRepository.saveAll(List.of(draft, active));

        Specification<Product> spec = ProductSpecifications.byStatus(ProductStatus.ACTIVE);
        List<Product> results = productRepository.findAll(spec);

        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(p -> p.getStatus() == ProductStatus.ACTIVE);
    }

    @Test
    void shouldFilterByPriceRange() {
        Product cheap = Product.builder()
                .name("Cheap Widget")
                .price(new BigDecimal("5.00"))
                .stock(1)
                .status(ProductStatus.DRAFT)
                .build();
        Product mid = Product.builder()
                .name("Mid Widget")
                .price(new BigDecimal("25.00"))
                .stock(1)
                .status(ProductStatus.DRAFT)
                .build();
        Product expensive = Product.builder()
                .name("Expensive Widget")
                .price(new BigDecimal("100.00"))
                .stock(1)
                .status(ProductStatus.DRAFT)
                .build();
        productRepository.saveAll(List.of(cheap, mid, expensive));

        Specification<Product> spec = Specification
                .where(ProductSpecifications.minPrice(new BigDecimal("10.00")))
                .and(ProductSpecifications.maxPrice(new BigDecimal("50.00")));
        List<Product> results = productRepository.findAll(spec);

        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(p ->
                p.getPrice().compareTo(new BigDecimal("10.00")) >= 0
                && p.getPrice().compareTo(new BigDecimal("50.00")) <= 0
        );
    }

    @Test
    void shouldReturnNextPageUsingKeysetCursor() {
        // Save three products; keyset should return records after the cursor
        Product p1 = productRepository.save(Product.builder()
                .name("Keyset Widget A")
                .price(new BigDecimal("10.00"))
                .stock(1)
                .status(ProductStatus.DRAFT)
                .build());
        Product p2 = productRepository.save(Product.builder()
                .name("Keyset Widget B")
                .price(new BigDecimal("10.00"))
                .stock(1)
                .status(ProductStatus.DRAFT)
                .build());
        Product p3 = productRepository.save(Product.builder()
                .name("Keyset Widget C")
                .price(new BigDecimal("10.00"))
                .stock(1)
                .status(ProductStatus.DRAFT)
                .build());

        // Use p1 as cursor — expect p2 and p3 in next page
        Specification<Product> afterP1 = ProductSpecifications.keysetAfter(p1.getCreatedAt(), p1.getId());
        List<Product> nextPage = productRepository.findAll(afterP1);

        assertThat(nextPage).doesNotContain(p1);
        assertThat(nextPage).contains(p2, p3);
    }

    @Test
    void shouldExecuteListQueryWithoutNPlusOneQueries() {
        // Save a small batch of products
        for (int i = 0; i < 5; i++) {
            productRepository.save(Product.builder()
                    .name("NPlusOne Widget " + i)
                    .price(new BigDecimal("10.00"))
                    .stock(i)
                    .status(ProductStatus.DRAFT)
                    .build());
        }

        // Reset counter after setup
        QueryCountHolder.clear();

        // List all via findAll — must be a single SELECT (no N+1)
        List<Product> all = productRepository.findAll();
        assertThat(all).hasSizeGreaterThanOrEqualTo(5);

        // datasource-proxy: expect exactly 1 SELECT query
        long selectCount = QueryCountHolder.getGrandTotal().getSelect();
        assertThat(selectCount)
                .as("Expected exactly 1 SELECT query (no N+1), but got %d", selectCount)
                .isEqualTo(1);
    }
}
