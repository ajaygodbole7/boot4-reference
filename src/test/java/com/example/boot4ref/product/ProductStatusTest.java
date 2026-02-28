package com.example.boot4ref.product;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ProductStatus lifecycle transitions.
 * DRAFT -> ACTIVE -> DISCONTINUED is the only valid path.
 */
class ProductStatusTest {

    @Test
    void draftCanTransitionToActive() {
        assertThat(ProductStatus.DRAFT.canTransitionTo(ProductStatus.ACTIVE)).isTrue();
    }

    @Test
    void activeCanTransitionToDiscontinued() {
        assertThat(ProductStatus.ACTIVE.canTransitionTo(ProductStatus.DISCONTINUED)).isTrue();
    }

    @Test
    void draftCannotTransitionToDiscontinued() {
        assertThat(ProductStatus.DRAFT.canTransitionTo(ProductStatus.DISCONTINUED)).isFalse();
    }

    @Test
    void activeCannotTransitionToDraft() {
        assertThat(ProductStatus.ACTIVE.canTransitionTo(ProductStatus.DRAFT)).isFalse();
    }

    @Test
    void discontinuedCannotTransitionToAnything() {
        assertThat(ProductStatus.DISCONTINUED.canTransitionTo(ProductStatus.DRAFT)).isFalse();
        assertThat(ProductStatus.DISCONTINUED.canTransitionTo(ProductStatus.ACTIVE)).isFalse();
        assertThat(ProductStatus.DISCONTINUED.canTransitionTo(ProductStatus.DISCONTINUED)).isFalse();
    }

    @Test
    void draftCannotTransitionToSelf() {
        assertThat(ProductStatus.DRAFT.canTransitionTo(ProductStatus.DRAFT)).isFalse();
    }

    @Test
    void activeCannotTransitionToSelf() {
        assertThat(ProductStatus.ACTIVE.canTransitionTo(ProductStatus.ACTIVE)).isFalse();
    }
}
