package com.example.boot4ref.product.specification;

import com.example.boot4ref.product.Product;
import com.example.boot4ref.product.ProductStatus;
import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/**
 * JPA Specification builders for {@link Product} queries.
 *
 * <p>Each builder is a pure static factory returning a composable {@link Specification}.
 * Null parameters produce a no-op (always-true) specification via a null predicate.
 * Combine with {@code Specification.where(a).and(b)} for multi-criteria queries.
 *
 * <p>The keyset cursor uses a composite {@code (createdAt, id)} predicate to support
 * stable forward pagination without OFFSET (avoids drift on concurrent inserts).
 *
 * <p>Note: Spring Data JPA (Boot 4) has an overload ambiguity with {@code Specification.where(null)}.
 * Instead, null-guard returns a lambda that returns null (JPA Criteria treats null predicates
 * as no restriction — equivalent to {@code 1=1}).
 */
public final class ProductSpecifications {

    private ProductSpecifications() {}

    /**
     * Filters products by exact status match. Returns a no-op spec when status is null.
     */
    public static Specification<Product> byStatus(@Nullable ProductStatus status) {
        if (status == null) {
            return (root, query, cb) -> null;
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /**
     * Filters products with price >= minPrice. Returns a no-op spec when minPrice is null.
     */
    public static Specification<Product> minPrice(@Nullable BigDecimal minPrice) {
        if (minPrice == null) {
            return (root, query, cb) -> null;
        }
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("price"), minPrice);
    }

    /**
     * Filters products with price <= maxPrice. Returns a no-op spec when maxPrice is null.
     */
    public static Specification<Product> maxPrice(@Nullable BigDecimal maxPrice) {
        if (maxPrice == null) {
            return (root, query, cb) -> null;
        }
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("price"), maxPrice);
    }

    /**
     * Keyset cursor: returns products created after the given (createdAt, id) pair.
     *
     * <p>Predicate: {@code (createdAt > afterCreatedAt) OR (createdAt = afterCreatedAt AND id > afterId)}
     * This provides stable, duplicate-free pagination even when multiple products share
     * the same {@code createdAt} timestamp (e.g., bulk inserts within the same millisecond).
     *
     * <p>Returns a no-op spec when either parameter is null.
     *
     * @param afterCreatedAt the createdAt of the last seen product
     * @param afterId        the id of the last seen product
     */
    public static Specification<Product> keysetAfter(@Nullable Instant afterCreatedAt, @Nullable Long afterId) {
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
