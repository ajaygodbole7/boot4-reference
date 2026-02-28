CREATE TABLE products (
    id              BIGINT          PRIMARY KEY,
    name            VARCHAR(255)    NOT NULL,
    description     TEXT,
    price           NUMERIC(19,4)   NOT NULL,
    stock           INTEGER         NOT NULL DEFAULT 0,
    status          VARCHAR(50)     NOT NULL DEFAULT 'DRAFT',
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL,
    updated_at      TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_products_status ON products(status);
CREATE INDEX idx_products_created_at_id ON products(created_at, id);
