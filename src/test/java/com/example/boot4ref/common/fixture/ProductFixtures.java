package com.example.boot4ref.common.fixture;

import com.example.boot4ref.product.Product;
import com.example.boot4ref.product.ProductStatus;
import com.example.boot4ref.product.rest.ProductCreateRequest;
import net.datafaker.Faker;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Shared test fixtures for the Product domain using DataFaker.
 */
public final class ProductFixtures {

    private static final Faker FAKER = new Faker();

    private ProductFixtures() {}

    /** Random product in DRAFT status with realistic commerce data. */
    public static Product randomProduct() {
        return Product.builder()
                .name(productName())
                .description(FAKER.lorem().sentence(8))
                .price(randomPrice())
                .stock(FAKER.number().numberBetween(1, 500))
                .status(ProductStatus.DRAFT)
                .build();
    }

    /** Random product already in ACTIVE status — ready for ordering. */
    public static Product randomActiveProduct() {
        return Product.builder()
                .name(productName())
                .description(FAKER.lorem().sentence(8))
                .price(randomPrice())
                .stock(FAKER.number().numberBetween(10, 500))
                .status(ProductStatus.ACTIVE)
                .build();
    }

    /** Random ProductCreateRequest that satisfies all validation constraints. */
    public static ProductCreateRequest randomProductCreateRequest() {
        return new ProductCreateRequest(
                productName(),
                FAKER.lorem().sentence(8),
                randomPrice(),
                FAKER.number().numberBetween(1, 500));
    }

    private static String productName() {
        // Ensures name is 3-100 chars (validation constraint)
        return FAKER.commerce().productName();
    }

    private static BigDecimal randomPrice() {
        // Positive, 2 decimal places
        double raw = FAKER.number().randomDouble(2, 1, 999);
        return BigDecimal.valueOf(raw).setScale(2, RoundingMode.HALF_UP);
    }
}
