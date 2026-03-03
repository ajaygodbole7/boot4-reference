package com.example.boot4ref.order;

import com.example.boot4ref.common.AbstractAuditingEntity;
import com.example.boot4ref.product.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * JPA entity representing a single line item within an Order.
 *
 * <p>Bidirectional relationship: owned side of Order → OrderLines.
 * Product reference is unidirectional (no cascade — Product has independent lifecycle).
 * {@code unitPrice} is a snapshot captured at order time.
 */
@Entity
@Table(name = "order_lines")
@Getter
@Setter
public class OrderLine extends AbstractAuditingEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    // No cascade — Product has independent lifecycle
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    // Price snapshot at order time — not a live reference to Product.price
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    protected OrderLine() {}

    @Override
    public String toString() {
        return "OrderLine{id=" + getId() + "}";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Product product;
        private Integer quantity;
        private BigDecimal unitPrice;

        private Builder() {}

        public Builder product(Product product) { this.product = product; return this; }
        public Builder quantity(Integer quantity) { this.quantity = quantity; return this; }
        public Builder unitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; return this; }

        public OrderLine build() {
            OrderLine line = new OrderLine();
            line.product = this.product;
            line.quantity = this.quantity;
            line.unitPrice = this.unitPrice;
            return line;
        }
    }
}
