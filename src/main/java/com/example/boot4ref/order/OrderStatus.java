package com.example.boot4ref.order;

/**
 * Lifecycle status for an Order.
 *
 * <p>Valid transitions:
 * <ul>
 *   <li>PLACED -> CONFIRMED (seller confirms)</li>
 *   <li>PLACED -> CANCELLED (buyer cancels)</li>
 *   <li>CONFIRMED -> SHIPPED (order ships)</li>
 *   <li>CONFIRMED -> CANCELLED (seller cancels)</li>
 *   <li>SHIPPED -> DELIVERED (delivery confirmed)</li>
 * </ul>
 * All other transitions are invalid and will be rejected.
 */
public enum OrderStatus {

    PLACED {
        @Override
        public boolean canTransitionTo(OrderStatus next) {
            return next == CONFIRMED || next == CANCELLED;
        }
    },

    CONFIRMED {
        @Override
        public boolean canTransitionTo(OrderStatus next) {
            return next == SHIPPED || next == CANCELLED;
        }
    },

    SHIPPED {
        @Override
        public boolean canTransitionTo(OrderStatus next) {
            return next == DELIVERED;
        }
    },

    DELIVERED {
        @Override
        public boolean canTransitionTo(OrderStatus next) {
            return false;
        }
    },

    CANCELLED {
        @Override
        public boolean canTransitionTo(OrderStatus next) {
            return false;
        }
    };

    /**
     * Returns true if this status can legally transition to {@code next}.
     *
     * @param next the target status
     * @return true if the transition is valid
     */
    public abstract boolean canTransitionTo(OrderStatus next);
}
