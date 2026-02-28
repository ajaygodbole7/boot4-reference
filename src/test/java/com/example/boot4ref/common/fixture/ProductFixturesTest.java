package com.example.boot4ref.common.fixture;

import com.example.boot4ref.product.Product;
import com.example.boot4ref.product.ProductStatus;
import com.example.boot4ref.product.rest.ProductCreateRequest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that ProductFixtures generates data meeting all validation constraints.
 */
class ProductFixturesTest {

    @RepeatedTest(10)
    void randomProductShouldMeetConstraints() {
        Product product = ProductFixtures.randomProduct();

        assertThat(product.getName()).hasSizeBetween(3, 100);
        assertThat(product.getDescription()).isNotBlank();
        assertThat(product.getPrice()).isGreaterThan(BigDecimal.ZERO);
        assertThat(product.getStock()).isPositive();
        assertThat(product.getStatus()).isEqualTo(ProductStatus.DRAFT);
    }

    @RepeatedTest(10)
    void randomActiveProductShouldBeActive() {
        Product product = ProductFixtures.randomActiveProduct();

        assertThat(product.getName()).hasSizeBetween(3, 100);
        assertThat(product.getPrice()).isGreaterThan(BigDecimal.ZERO);
        assertThat(product.getStock()).isGreaterThanOrEqualTo(10);
        assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    @RepeatedTest(10)
    void randomCreateRequestShouldMeetValidationConstraints() {
        ProductCreateRequest request = ProductFixtures.randomProductCreateRequest();

        assertThat(request.name()).hasSizeBetween(3, 100);
        assertThat(request.description()).isNotBlank();
        assertThat(request.price()).isGreaterThan(BigDecimal.ZERO);
        assertThat(request.stock()).isPositive();
    }

    @Test
    void randomProductsShouldVary() {
        Product p1 = ProductFixtures.randomProduct();
        Product p2 = ProductFixtures.randomProduct();

        // At least one field should differ across two random products
        boolean namesDiffer = !p1.getName().equals(p2.getName());
        boolean pricesDiffer = p1.getPrice().compareTo(p2.getPrice()) != 0;
        boolean stocksDiffer = !p1.getStock().equals(p2.getStock());
        assertThat(namesDiffer || pricesDiffer || stocksDiffer)
                .as("Two random products should not be identical")
                .isTrue();
    }
}
