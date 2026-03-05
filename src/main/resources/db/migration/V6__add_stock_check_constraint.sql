-- Add CHECK constraint to prevent negative stock at the database level.
-- Bean validation and service logic already guard against this, but this
-- provides a safety net for direct SQL updates or concurrency edge cases.
ALTER TABLE products ADD CONSTRAINT chk_stock_non_negative CHECK (stock >= 0);
