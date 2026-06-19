CREATE TABLE admin_messages (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES customers(id),
    direction VARCHAR(10) NOT NULL,
    message TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_admin_messages_customer ON admin_messages(customer_id);
CREATE INDEX idx_admin_messages_created_at ON admin_messages(created_at);
