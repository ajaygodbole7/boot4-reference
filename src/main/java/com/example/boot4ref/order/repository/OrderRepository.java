package com.example.boot4ref.order.repository;

import com.example.boot4ref.order.Order;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA repository for {@link Order} entities.
 *
 * <p>JOIN FETCH for single-entity loads (N+1 prevention tier 1).
 * {@code @EntityGraph} for paginated/filtered queries (tier 2).
 */
public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    /**
     * Loads a single order with its line items and their products in one query.
     * JOIN FETCH for single-entity load — N+1 prevention tier 1.
     */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.orderLines ol LEFT JOIN FETCH ol.product WHERE o.id = :id")
    Optional<Order> findByIdWithLines(Long id);

    /**
     * Finds an existing order by idempotency key.
     */
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    /**
     * Filtered queries with @EntityGraph — N+1 prevention tier 2.
     * JOIN FETCH breaks pagination with collections; EntityGraph doesn't.
     */
    @EntityGraph(attributePaths = {"orderLines", "orderLines.product"})
    List<Order> findAll(Specification<Order> spec);

    /**
     * Paginated filtered queries with @EntityGraph — N+1 prevention tier 2.
     * Overrides JpaSpecificationExecutor default which lacks eager fetching.
     */
    @EntityGraph(attributePaths = {"orderLines", "orderLines.product"})
    Page<Order> findAll(Specification<Order> spec, Pageable pageable);
}
