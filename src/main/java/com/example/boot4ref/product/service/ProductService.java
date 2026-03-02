package com.example.boot4ref.product.service;

import com.example.boot4ref.config.ApplicationProperties;
import com.example.boot4ref.product.Product;
import com.example.boot4ref.product.ProductStatus;
import com.example.boot4ref.product.exception.ProductConflictException;
import com.example.boot4ref.product.exception.ProductNotFoundException;
import com.example.boot4ref.product.repository.ProductRepository;
import com.example.boot4ref.product.rest.ProductCreateRequest;
import com.example.boot4ref.product.rest.ProductPatchRequest;
import com.example.boot4ref.product.rest.ProductResponse;
import com.example.boot4ref.product.rest.ProductUpdateRequest;
import com.example.boot4ref.product.specification.ProductSpecifications;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for Product domain operations.
 *
 * <p>All write operations are {@code @Transactional}. Read operations use
 * {@code @Transactional(readOnly = true)} for Hibernate session optimisation.
 *
 * <p>No Lombok -- manual Logger per CLAUDE.md (Lombok restricted to @Entity).
 */
@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);
    private static final int OPTIMISTIC_LOCK_MAX_ATTEMPTS = 3;
    private static final int OPTIMISTIC_LOCK_RETRY_DELAY_MS = 100;

    private final ProductRepository productRepository;
    private final int defaultPageSize;

    public ProductService(ProductRepository productRepository, ApplicationProperties properties) {
        this.productRepository = productRepository;
        this.defaultPageSize = properties.getPagination().getDefaultPageSize();
    }

    /**
     * Returns filtered products using Specification composition and optional keyset cursor.
     *
     * <p>All parameters are optional; omitting them returns all products.
     * Keyset cursor ({@code afterId}) enables stable forward pagination without OFFSET.
     * TSID IDs are time-ordered, so id alone gives chronological ordering.
     *
     * @param status   filter by product status (exact match)
     * @param minPrice filter by minimum price (inclusive)
     * @param maxPrice filter by maximum price (inclusive)
     * @param afterId  keyset cursor: the id of the last seen product
     * @param limit    maximum number of results (default 20 if null)
     */
    @Transactional(readOnly = true)
    public List<ProductResponse> listFiltered(
            @Nullable ProductStatus status,
            @Nullable BigDecimal minPrice,
            @Nullable BigDecimal maxPrice,
            @Nullable Long afterId,
            @Nullable Integer limit) {

        Specification<Product> spec = ProductSpecifications.byStatus(status)
                .and(ProductSpecifications.minPrice(minPrice))
                .and(ProductSpecifications.maxPrice(maxPrice))
                .and(ProductSpecifications.keysetAfter(afterId));

        int pageSize = (limit != null && limit > 0) ? limit : defaultPageSize;

        return productRepository.findAll(spec,
                        PageRequest.of(0, pageSize, Sort.by(Sort.Direction.ASC, "id")))
                .map(this::toResponse)
                .getContent();
    }

    /**
     * Finds a product by ID.
     *
     * @throws ProductNotFoundException if not found
     */
    @Transactional(readOnly = true)
    public ProductResponse findById(Long id) {
        return toResponse(findOrThrow(id));
    }

    /**
     * Creates a new product with DRAFT status.
     */
    @Transactional
    public ProductResponse create(ProductCreateRequest request) {
        Product product = Product.builder()
                .name(request.name())
                .description(request.description())
                .price(request.price())
                .stock(request.stock())
                .status(ProductStatus.DRAFT)
                .build();
        Product saved = productRepository.save(product);
        log.debug("Created product id={} name={}", saved.getId(), saved.getName());
        return toResponse(saved);
    }

    /**
     * Updates mutable product fields (name, description, price, stock).
     * Status transitions must use {@link #transition(Long, ProductStatus)}.
     *
     * <p>{@code @Retryable} intercepts {@link ObjectOptimisticLockingFailureException}
     * thrown by {@code save()} when a concurrent writer advanced {@code @Version}.
     * Each retry is a fresh {@code @Transactional} invocation — a new EntityManager
     * is opened, the entity is reloaded with the current version, and the update
     * is re-attempted. After {@code maxAttempts} exhausted, the exception propagates
     * to {@link com.example.boot4ref.common.rest.ExceptionTranslator} which returns 409.
     *
     * @throws ProductNotFoundException if not found
     */
    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = OPTIMISTIC_LOCK_MAX_ATTEMPTS,
            backoff = @Backoff(delay = OPTIMISTIC_LOCK_RETRY_DELAY_MS, multiplier = 2, random = true))
    @Transactional
    public ProductResponse update(Long id, ProductUpdateRequest request) {
        Product product = findOrThrow(id);
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStock(request.stock());
        Product saved = productRepository.save(product);
        log.debug("Updated product id={}", saved.getId());
        return toResponse(saved);
    }

    /**
     * Partial update: applies only the non-null fields from the patch request.
     * Fields absent from the request (deserialized as null) are left unchanged.
     *
     * <p>{@code @Retryable} provides the same optimistic-lock retry as
     * {@link #update(Long, ProductUpdateRequest)} — see that method for rationale.
     *
     * @throws ProductNotFoundException if not found
     */
    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = OPTIMISTIC_LOCK_MAX_ATTEMPTS,
            backoff = @Backoff(delay = OPTIMISTIC_LOCK_RETRY_DELAY_MS, multiplier = 2, random = true))
    @Transactional
    public ProductResponse patch(Long id, ProductPatchRequest request) {
        Product product = findOrThrow(id);
        if (request.name() != null) {
            product.setName(request.name());
        }
        if (request.description() != null) {
            product.setDescription(request.description());
        }
        if (request.price() != null) {
            product.setPrice(request.price());
        }
        if (request.stock() != null) {
            product.setStock(request.stock());
        }
        Product saved = productRepository.save(product);
        log.debug("Patched product id={}", saved.getId());
        return toResponse(saved);
    }

    /**
     * Deletes a product by ID.
     *
     * @throws ProductNotFoundException if not found
     */
    @Transactional
    public void delete(Long id) {
        Product product = findOrThrow(id);
        productRepository.delete(product);
        log.debug("Deleted product id={}", id);
    }

    /**
     * Transitions a product to a new status, enforcing the lifecycle state machine.
     *
     * @throws ProductNotFoundException  if not found
     * @throws ProductConflictException  if the transition is not allowed
     */
    @Transactional
    public ProductResponse transition(Long id, ProductStatus newStatus) {
        Product product = findOrThrow(id);
        ProductStatus current = product.getStatus();
        if (!current.canTransitionTo(newStatus)) {
            throw new ProductConflictException(
                    "Invalid status transition from " + current + " to " + newStatus
            );
        }
        product.setStatus(newStatus);
        Product saved = productRepository.save(product);
        log.info("Transitioned product id={} from {} to {}", id, current, newStatus);
        return toResponse(saved);
    }

    // -------------------------------------------------------------------------

    private Product findOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    private ProductResponse toResponse(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStock(),
                product.getStatus().name(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
