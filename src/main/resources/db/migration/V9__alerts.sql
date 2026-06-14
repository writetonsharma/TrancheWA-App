CREATE TABLE alerts (
    id          BIGSERIAL PRIMARY KEY,
    type        VARCHAR(50)  NOT NULL,
    message     TEXT         NOT NULL,
    order_id    BIGINT       REFERENCES orders(id),
    customer_phone VARCHAR(20),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    resolved    BOOLEAN      NOT NULL DEFAULT FALSE,
    resolved_at TIMESTAMP
);
