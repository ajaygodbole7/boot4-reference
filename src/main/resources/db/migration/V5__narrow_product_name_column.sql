-- Narrow products.name from VARCHAR(255) to VARCHAR(100) to match ProductConstraints.NAME_MAX_LENGTH.
-- Bean validation already caps at 100 chars, so no existing data exceeds this.
ALTER TABLE products ALTER COLUMN name TYPE VARCHAR(100);
