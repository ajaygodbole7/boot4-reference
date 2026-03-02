-- TSID IDs are time-ordered, so ORDER BY id gives chronological order.
-- The composite (created_at, id) indexes are no longer needed — PK covers ORDER BY id.
DROP INDEX idx_products_created_at_id;
DROP INDEX idx_orders_created_at_id;
