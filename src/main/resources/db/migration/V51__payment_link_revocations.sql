-- Transactional outbox and dispatch fence. Only confirmed provider revocation removes it.
CREATE TABLE payment_link_revocations (
    payment_id bigint NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    order_id bigint NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    gateway_link_id varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (payment_id, gateway_link_id)
);