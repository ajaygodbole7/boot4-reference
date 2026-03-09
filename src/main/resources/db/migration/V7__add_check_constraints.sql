-- Add CHECK constraints for defense-in-depth beyond Bean Validation.
-- These guard against direct SQL updates and concurrency edge cases.

ALTER TABLE products ADD CONSTRAINT chk_price_positive CHECK (price > 0);

ALTER TABLE orders ADD CONSTRAINT chk_total_amount_non_negative CHECK (total_amount >= 0);

ALTER TABLE order_lines ADD CONSTRAINT chk_quantity_positive CHECK (quantity > 0);
ALTER TABLE order_lines ADD CONSTRAINT chk_unit_price_positive CHECK (unit_price > 0);
