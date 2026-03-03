package com.example.boot4ref.order;

import com.example.boot4ref.common.AbstractAuditingEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA entity representing a customer order.
 *
 * <p>Extends {@link AbstractAuditingEntity} for TSID-based ID generation and
 * optimistic locking via {@code @Version}. Follows Vlad Mihalcea JPA patterns:
 * bidirectional sync methods, {@code List} (never {@code Set}), constant hashCode.
 *
 * <p>{@code totalAmount} is a derived field computed from order lines on write.
 * {@code idempotencyKey} ensures at-most-once order creation.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
public class Order extends AbstractAuditingEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(name = "idempotency_key", unique = true, length = 255)
    private String idempotencyKey;

    // Vlad Mihalcea: List not Set, CascadeType.ALL + orphanRemoval, bidirectional sync
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<OrderLine> orderLines = new ArrayList<>();

    protected Order() {}

    /**
     * Adds a line item and sets the bidirectional reference.
     * Without this, Hibernate's in-memory model diverges from what it persists.
     */
    public void addOrderLine(OrderLine line) {
        orderLines.add(line);
        line.setOrder(this);
    }

    /**
     * Removes a line item and clears the bidirectional reference.
     */
    public void removeOrderLine(OrderLine line) {
        orderLines.remove(line);
        line.setOrder(null);
    }

    /**
     * Computes total from line items: SUM(unitPrice * quantity).
     */
    public void computeTotal() {
        this.totalAmount = orderLines.stream()
                .map(line -> line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private OrderStatus status;
        private BigDecimal totalAmount = BigDecimal.ZERO;
        private String idempotencyKey;

        private Builder() {}

        public Builder status(OrderStatus status) { this.status = status; return this; }
        public Builder totalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; return this; }
        public Builder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }

        public Order build() {
            Order o = new Order();
            o.status = this.status;
            o.totalAmount = this.totalAmount;
            o.idempotencyKey = this.idempotencyKey;
            return o;
        }
    }
}
