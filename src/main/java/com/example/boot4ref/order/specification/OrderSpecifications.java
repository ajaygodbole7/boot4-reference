package com.example.boot4ref.order.specification;

import com.example.boot4ref.order.Order;
import com.example.boot4ref.order.OrderStatus;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/**
 * JPA Specification builders for {@link Order} queries.
 *
 * <p>Follows the same pattern as ProductSpecifications: null parameters
 * produce a no-op specification. Keyset cursor uses composite (createdAt, id).
 */
public final class OrderSpecifications {

    private OrderSpecifications() {}

    public static Specification<Order> byStatus(@Nullable OrderStatus status) {
        if (status == null) {
            return (root, query, cb) -> null;
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /**
     * Keyset cursor: returns orders created after the given (createdAt, id) pair.
     * Predicate: (createdAt > after) OR (createdAt = after AND id > afterId)
     */
    public static Specification<Order> keysetAfter(@Nullable Instant afterCreatedAt, @Nullable Long afterId) {
        if (afterCreatedAt == null || afterId == null) {
            return (root, query, cb) -> null;
        }
        return (root, query, cb) -> cb.or(
                cb.greaterThan(root.get("createdAt"), afterCreatedAt),
                cb.and(
                        cb.equal(root.get("createdAt"), afterCreatedAt),
                        cb.greaterThan(root.get("id"), afterId)
                )
        );
    }
}
