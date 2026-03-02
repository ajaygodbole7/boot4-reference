package com.example.boot4ref.order.specification;

import com.example.boot4ref.order.Order;
import com.example.boot4ref.order.OrderStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/**
 * JPA Specification builders for {@link Order} queries.
 *
 * <p>Follows the same pattern as ProductSpecifications: null parameters
 * produce a no-op specification. Keyset cursor uses id-only (TSID is time-ordered).
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
     * Keyset cursor: returns orders with {@code id > afterId}.
     * TSID IDs are time-ordered, so id alone gives chronological pagination.
     */
    public static Specification<Order> keysetAfter(@Nullable Long afterId) {
        if (afterId == null) {
            return (root, query, cb) -> null;
        }
        return (root, query, cb) -> cb.greaterThan(root.get("id"), afterId);
    }
}
