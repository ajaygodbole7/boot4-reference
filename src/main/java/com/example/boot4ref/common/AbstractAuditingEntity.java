package com.example.boot4ref.common;

import io.hypersistence.tsid.TSID;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@MappedSuperclass
@Getter
public abstract class AbstractAuditingEntity {

    private static final TSID.Factory TSID_FACTORY = TSID.Factory.builder().build();

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void prePersist() {
        if (this.id == null) {
            this.id = TSID_FACTORY.generate().toLong();
        }
        if (this.createdAt == null) {
            var now = Instant.now();
            this.createdAt = now;
            this.updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AbstractAuditingEntity that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public final int hashCode() {
        return getClass().hashCode();
    }
}
