CREATE TABLE orders (
    id              BIGINT          PRIMARY KEY,
    status          VARCHAR(50)     NOT NULL DEFAULT 'PLACED',
    total_amount    NUMERIC(19,4)   NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(255)    UNIQUE,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL,
    updated_at      TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_created_at_id ON orders(created_at, id);

CREATE TABLE order_lines (
    id              BIGINT          PRIMARY KEY,
    order_id        BIGINT          NOT NULL REFERENCES orders(id),
    product_id      BIGINT          NOT NULL REFERENCES products(id),
    quantity        INTEGER         NOT NULL,
    unit_price      NUMERIC(19,4)   NOT NULL,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL,
    updated_at      TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_order_lines_order_id ON order_lines(order_id);
CREATE INDEX idx_order_lines_product_id ON order_lines(product_id);
