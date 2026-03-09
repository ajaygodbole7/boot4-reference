package com.example.boot4ref.order.service;

import com.example.boot4ref.config.ApplicationProperties;
import com.example.boot4ref.order.Order;
import com.example.boot4ref.order.OrderLine;
import com.example.boot4ref.order.OrderStatus;
import com.example.boot4ref.order.event.DomainEvent;
import com.example.boot4ref.order.exception.DuplicateLineItemException;
import com.example.boot4ref.order.exception.UnorderableProductException;
import com.example.boot4ref.order.exception.InsufficientStockException;
import com.example.boot4ref.order.exception.OrderConflictException;
import com.example.boot4ref.order.exception.OrderNotFoundException;
import com.example.boot4ref.order.repository.OrderRepository;
import com.example.boot4ref.order.rest.OrderCreateRequest;
import com.example.boot4ref.order.rest.OrderLineRequest;
import com.example.boot4ref.order.rest.OrderLineResponse;
import com.example.boot4ref.order.rest.OrderResponse;
import com.example.boot4ref.order.specification.OrderSpecifications;
import com.example.boot4ref.outbox.OutboxWriter;
import com.example.boot4ref.product.Product;
import com.example.boot4ref.product.ProductStatus;
import com.example.boot4ref.product.exception.ProductNotFoundException;
import com.example.boot4ref.product.repository.ProductRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for Order domain operations.
 *
 * <p>Create acquires PESSIMISTIC_WRITE locks on products (sorted by PK to prevent deadlocks),
 * validates business rules, decrements stock, snapshots prices, and persists everything
 * in a single transaction.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final OutboxWriter outboxWriter;
    private final int defaultPageSize;
    private final int maxPageSize;

    public OrderService(OrderRepository orderRepository,
                        ProductRepository productRepository,
                        OutboxWriter outboxWriter,
                        ApplicationProperties properties) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.outboxWriter = outboxWriter;
        this.defaultPageSize = properties.getPagination().getDefaultPageSize();
        this.maxPageSize = properties.getPagination().getMaxPageSize();
    }

    /**
     * Creates a new order with line items.
     *
     * <p>Business rules enforced:
     * <ul>
     *   <li>Snapshots product price at order time</li>
     *   <li>Idempotency key — returns existing order if key matches</li>
     *   <li>PESSIMISTIC_WRITE lock on products, lock in PK order</li>
     *   <li>Insufficient stock → 422</li>
     *   <li>Non-ACTIVE product → 422</li>
     *   <li>Total computed from lines</li>
     * </ul>
     */
    @Transactional
    public OrderCreateResult create(OrderCreateRequest request, @Nullable String idempotencyKey) {
        // Idempotency check — return existing order if key matches
        if (idempotencyKey != null) {
            var existing = orderRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.debug("Returning existing order for idempotency key={}", idempotencyKey);
                return new OrderCreateResult(toResponse(existing.get()), false);
            }
        }

        Order order = Order.builder()
                .status(OrderStatus.PLACED)
                .idempotencyKey(idempotencyKey)
                .build();

        // Sort by product ID to acquire locks in consistent order (prevent deadlocks)
        List<OrderLineRequest> sortedItems = request.items().stream()
                .sorted(Comparator.comparing(OrderLineRequest::productId))
                .toList();

        // Reject duplicate product IDs before acquiring locks
        Set<Long> seen = new HashSet<>();
        for (OrderLineRequest item : sortedItems) {
            if (!seen.add(item.productId())) {
                throw new DuplicateLineItemException(item.productId());
            }
        }

        for (OrderLineRequest item : sortedItems) {
            // PESSIMISTIC_WRITE lock — timeout defined by ProductRepository.LOCK_TIMEOUT_MS
            Product product = productRepository.findWithLockById(item.productId())
                    .orElseThrow(() -> new ProductNotFoundException(item.productId()));

            // Only ACTIVE products can be ordered
            if (product.getStatus() != ProductStatus.ACTIVE) {
                throw new UnorderableProductException(product.getId(), product.getStatus());
            }

            // Insufficient stock check
            if (item.quantity() > product.getStock()) {
                throw new InsufficientStockException(
                        product.getId(), item.quantity(), product.getStock());
            }

            // Decrement stock
            product.setStock(product.getStock() - item.quantity());

            // Price snapshot at order time
            OrderLine line = OrderLine.builder()
                    .product(product)
                    .quantity(item.quantity())
                    .unitPrice(product.getPrice())
                    .build();
            order.addOrderLine(line);
        }

        // Compute total from lines
        order.computeTotal();
        Order saved = orderRepository.saveAndFlush(order);

        // Outbox: insert event in same transaction
        outboxWriter.stageEvent(new DomainEvent.OrderPlaced(
                saved.getId(), Instant.now(), saved.getTotalAmount(),
                saved.getOrderLines().size()));

        log.info("Created order id={} with {} lines, total={}",
                saved.getId(), saved.getOrderLines().size(), saved.getTotalAmount());
        return new OrderCreateResult(toResponse(saved), true);
    }

    /**
     * Finds an existing order by idempotency key.
     *
     * @throws OrderNotFoundException if no order matches the key
     */
    @Transactional(readOnly = true)
    public OrderResponse findByIdempotencyKey(String idempotencyKey) {
        return orderRepository.findByIdempotencyKey(idempotencyKey)
                .map(this::toResponse)
                .orElseThrow(() -> new OrderNotFoundException(
                        "No order found for idempotency key: " + idempotencyKey));
    }

    /**
     * Finds an order by ID with nested line items via JOIN FETCH.
     *
     * @throws OrderNotFoundException if not found
     */
    @Transactional(readOnly = true)
    public OrderResponse findById(Long id) {
        Order order = orderRepository.findByIdWithLines(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        return toResponse(order);
    }

    /**
     * Lists orders with optional filtering and keyset pagination.
     * TSID IDs are time-ordered, so id alone gives chronological ordering.
     */
    @Transactional(readOnly = true)
    public List<OrderResponse> listFiltered(
            @Nullable OrderStatus status,
            @Nullable Long afterId,
            @Nullable Integer limit) {

        Specification<Order> spec = OrderSpecifications.byStatus(status)
                .and(OrderSpecifications.keysetAfter(afterId));

        int pageSize = (limit != null && limit > 0) ? Math.min(limit, maxPageSize) : defaultPageSize;

        // Two-query pattern: first get IDs with correct LIMIT (no row inflation),
        // then batch-fetch full entity graph for those IDs.
        List<Long> ids = orderRepository.findAll(spec,
                        PageRequest.of(0, pageSize, Sort.by(Sort.Direction.ASC, "id")))
                .map(Order::getId)
                .getContent();

        if (ids.isEmpty()) {
            return List.of();
        }

        return orderRepository.findAllByIdIn(ids).stream()
                .sorted(Comparator.comparing(Order::getId))
                .map(this::toResponse)
                .toList();
    }

    /**
     * Transitions an order to a new status, enforcing the lifecycle state machine.
     * Cancellation restores stock.
     *
     * @throws OrderNotFoundException if not found
     * @throws OrderConflictException if the transition is not allowed
     */
    @Transactional
    public OrderResponse transition(Long id, OrderStatus newStatus) {
        Order order = orderRepository.findByIdWithLines(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        OrderStatus current = order.getStatus();
        if (!current.canTransitionTo(newStatus)) {
            throw new OrderConflictException(
                    "Invalid status transition from " + current + " to " + newStatus
            );
        }

        // Restore stock on cancellation
        if (newStatus == OrderStatus.CANCELLED) {
            restoreStock(order);
        }

        order.setStatus(newStatus);
        Order saved = orderRepository.saveAndFlush(order);

        // Outbox: insert transition event in same transaction
        outboxWriter.stageEvent(createTransitionEvent(saved.getId(), newStatus));

        log.info("Transitioned order id={} from {} to {}", id, current, newStatus);
        return toResponse(saved);
    }

    /**
     * Restores product stock for all line items in the order.
     * Uses pessimistic lock to prevent concurrent stock modifications.
     */
    private void restoreStock(Order order) {
        // Lock products in PK order to prevent deadlocks
        List<OrderLine> sortedLines = order.getOrderLines().stream()
                .sorted(Comparator.comparing(line -> line.getProduct().getId()))
                .toList();

        for (OrderLine line : sortedLines) {
            Product product = productRepository.findWithLockById(line.getProduct().getId())
                    .orElseThrow(() -> new ProductNotFoundException(line.getProduct().getId()));
            if (product.getStatus() == ProductStatus.DISCONTINUED) {
                log.warn("Skipping stock restore for DISCONTINUED product id={}", product.getId());
                continue;
            }
            product.setStock(product.getStock() + line.getQuantity());
            log.debug("Restored {} units to product id={}", line.getQuantity(), product.getId());
        }
    }

    private DomainEvent createTransitionEvent(Long orderId, OrderStatus newStatus) {
        Instant now = Instant.now();
        return switch (newStatus) {
            case CONFIRMED -> new DomainEvent.OrderConfirmed(orderId, now);
            case SHIPPED -> new DomainEvent.OrderShipped(orderId, now);
            case DELIVERED -> new DomainEvent.OrderDelivered(orderId, now);
            case CANCELLED -> new DomainEvent.OrderCancelled(orderId, now);
            case PLACED -> throw new IllegalStateException("Cannot transition to PLACED");
        };
    }

    // -------------------------------------------------------------------------

    private OrderResponse toResponse(Order order) {
        List<OrderLineResponse> lineResponses = order.getOrderLines().stream()
                .map(this::toLineResponse)
                .toList();
        return new OrderResponse(
                order.getId(),
                order.getStatus().name(),
                order.getTotalAmount(),
                order.getIdempotencyKey(),
                lineResponses,
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }

    private OrderLineResponse toLineResponse(OrderLine line) {
        return new OrderLineResponse(
                line.getId(),
                line.getProduct().getId(),
                line.getProduct().getName(),
                line.getQuantity(),
                line.getUnitPrice()
        );
    }
}
