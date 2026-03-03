package com.example.boot4ref.product;

import com.example.boot4ref.common.AbstractAuditingEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * JPA entity representing a product in the catalog.
 *
 * <p>Extends {@link AbstractAuditingEntity} for TSID-based ID generation and
 * optimistic locking via {@code @Version}. Follows Vlad Mihalcea JPA patterns:
 * constant hashCode, ID-based equals, final modifier on inherited audit methods.
 *
 * <p>Lombok restricted to {@code @Getter}/{@code @Setter} as per CLAUDE.md.
 * A static {@link #builder()} factory is provided manually to avoid
 * Lombok @Builder conflicts with the JPA-managed superclass.
 */
@Entity
@Table(name = "products")
@Getter
@Setter
public class Product extends AbstractAuditingEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "price", nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(name = "stock", nullable = false)
    private Integer stock;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private ProductStatus status;

    /**
     * JPA requires a no-arg constructor. Protected visibility prevents
     * accidental use while still allowing JPA/Hibernate proxy creation.
     */
    protected Product() {}

    /** Returns a new builder for constructing {@link Product} instances. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link Product}.
     * Audit fields (id, createdAt, updatedAt, version) are set by {@code @PrePersist}.
     */
    public static final class Builder {
        private String name;
        private String description;
        private BigDecimal price;
        private Integer stock;
        private ProductStatus status;

        private Builder() {}

        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder price(BigDecimal price) { this.price = price; return this; }
        public Builder stock(Integer stock) { this.stock = stock; return this; }
        public Builder status(ProductStatus status) { this.status = status; return this; }

        public Product build() {
            Product p = new Product();
            p.name = this.name;
            p.description = this.description;
            p.price = this.price;
            p.stock = this.stock;
            p.status = this.status;
            return p;
        }
    }
}
