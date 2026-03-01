package com.example.boot4ref.product.repository;

import com.example.boot4ref.product.Product;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

/**
 * Spring Data JPA repository for {@link Product} entities.
 *
 * <p>Extends {@link JpaSpecificationExecutor} to support Specification-based filtering
 * and keyset pagination without OFFSET.
 *
 * <p>TSID-based Long IDs align with the AbstractAuditingEntity strategy.
 */
public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    String LOCK_TIMEOUT_MS = "3000";

    /**
     * Finds all products matching the given specification.
     * Product has no lazy associations — no @EntityGraph needed.
     */
    List<Product> findAll(Specification<Product> spec);

    /**
     * Acquires a PESSIMISTIC_WRITE lock on a product row for stock decrement.
     * Lock timeout prevents indefinite waiting on contended rows.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = LOCK_TIMEOUT_MS))
    Optional<Product> findWithLockById(Long id);
}
