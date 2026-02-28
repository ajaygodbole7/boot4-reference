package com.example.boot4ref.product;

/**
 * Lifecycle status for a Product.
 *
 * <p>Valid transitions:
 * <ul>
 *   <li>DRAFT -> ACTIVE (publish for sale)</li>
 *   <li>ACTIVE -> DISCONTINUED (end of life)</li>
 * </ul>
 * All other transitions are invalid and will be rejected.
 */
public enum ProductStatus {

    DRAFT {
        @Override
        public boolean canTransitionTo(ProductStatus next) {
            return next == ACTIVE;
        }
    },

    ACTIVE {
        @Override
        public boolean canTransitionTo(ProductStatus next) {
            return next == DISCONTINUED;
        }
    },

    DISCONTINUED {
        @Override
        public boolean canTransitionTo(ProductStatus next) {
            return false;
        }
    };

    /**
     * Returns true if this status can legally transition to {@code next}.
     *
     * @param next the target status
     * @return true if the transition is valid
     */
    public abstract boolean canTransitionTo(ProductStatus next);
}
