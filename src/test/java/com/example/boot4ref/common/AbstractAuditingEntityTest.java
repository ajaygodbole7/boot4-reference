package com.example.boot4ref.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractAuditingEntityTest {

    private TestEntity entity;

    @BeforeEach
    void setUp() {
        entity = new TestEntity();
    }

    // --- TSID generation ---

    @Test
    void shouldGenerateTsidOnPrePersist() {
        assertThat(entity.getId()).isNull();

        entity.prePersist();

        assertThat(entity.getId()).isNotNull();
    }

    @Test
    void shouldNotOverwriteExistingIdOnPrePersist() {
        Long existingId = 42L;
        entity.setIdForTest(existingId);

        entity.prePersist();

        assertThat(entity.getId()).isEqualTo(existingId);
    }

    // --- Audit columns ---

    @Test
    void shouldPopulateCreatedAtOnPrePersist() {
        entity.prePersist();

        assertThat(entity.getCreatedAt()).isNotNull();
    }

    @Test
    void shouldPopulateUpdatedAtOnPrePersist() {
        entity.prePersist();

        assertThat(entity.getUpdatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isEqualTo(entity.getCreatedAt());
    }

    @Test
    void shouldNotOverwriteCreatedAtOnRepeatedPrePersist() {
        entity.prePersist();
        var originalCreatedAt = entity.getCreatedAt();

        entity.prePersist();

        assertThat(entity.getCreatedAt()).isEqualTo(originalCreatedAt);
    }

    @Test
    void shouldUpdateUpdatedAtOnPreUpdate() throws InterruptedException {
        entity.prePersist();
        var originalUpdatedAt = entity.getUpdatedAt();

        // 15ms ensures Instant.now() advances beyond timer resolution on all platforms
        Thread.sleep(15);

        entity.preUpdate();

        assertThat(entity.getUpdatedAt()).isNotEqualTo(originalUpdatedAt);
    }

    @Test
    void shouldNotChangeCreatedAtOnPreUpdate() {
        entity.prePersist();
        var originalCreatedAt = entity.getCreatedAt();

        entity.preUpdate();

        assertThat(entity.getCreatedAt()).isEqualTo(originalCreatedAt);
    }

    // --- Mihalcea equals/hashCode contract ---

    @Test
    void shouldBeEqualWhenSameId() {
        entity.prePersist();
        var other = new TestEntity();
        other.setIdForTest(entity.getId());

        assertThat(entity).isEqualTo(other);
        assertThat(other).isEqualTo(entity);
    }

    @Test
    void shouldNotBeEqualWhenDifferentIds() {
        entity.setIdForTest(1L);
        var other = new TestEntity();
        other.setIdForTest(2L);

        assertThat(entity).isNotEqualTo(other);
    }

    @Test
    void shouldNotBeEqualWhenIdIsNull() {
        var other = new TestEntity();

        // Both transient -- never equal
        assertThat(entity).isNotEqualTo(other);
        assertThat(other).isNotEqualTo(entity);

        // One persisted, one transient -- not equal
        other.prePersist();
        assertThat(entity).isNotEqualTo(other);
    }

    @Test
    void shouldReturnSameEntityAsEqualToItself() {
        assertThat(entity).isEqualTo(entity);

        entity.prePersist();
        assertThat(entity).isEqualTo(entity);
    }

    @Test
    void shouldReturnConstantHashCode() {
        int expected = TestEntity.class.hashCode();

        assertThat(entity.hashCode()).isEqualTo(expected);
    }

    @Test
    void shouldMaintainHashCodeConsistencyAcrossIdChanges() {
        int hashBefore = entity.hashCode();

        entity.prePersist();

        assertThat(entity.hashCode()).isEqualTo(hashBefore);
    }

    // --- @Version ---

    @Test
    void shouldHaveNullVersionBeforePersist() {
        assertThat(entity.getVersion()).isNull();
    }

    // --- Concrete test subclass ---

    static class TestEntity extends AbstractAuditingEntity {

        /** Test-only: sets id via reflection since AbstractAuditingEntity has no id setter. */
        void setIdForTest(Long id) {
            try {
                var field = AbstractAuditingEntity.class.getDeclaredField("id");
                field.setAccessible(true);
                field.set(this, id);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
