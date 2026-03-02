package com.example.boot4ref.common.fixture;

import com.example.boot4ref.order.rest.OrderCreateRequest;
import com.example.boot4ref.order.rest.OrderLineRequest;
import net.datafaker.Faker;

import java.util.List;

/**
 * Shared test fixtures for the Order domain using DataFaker.
 */
public final class OrderFixtures {

    private static final Faker FAKER = new Faker();

    private OrderFixtures() {}

    /** Random order request for a single product with 1-5 quantity. */
    public static OrderCreateRequest randomOrderCreateRequest(Long productId) {
        return new OrderCreateRequest(
                List.of(randomOrderLineRequest(productId)));
    }

    /** Random order line request for the given product. */
    public static OrderLineRequest randomOrderLineRequest(Long productId) {
        return new OrderLineRequest(
                productId,
                FAKER.number().numberBetween(1, 5));
    }
}
