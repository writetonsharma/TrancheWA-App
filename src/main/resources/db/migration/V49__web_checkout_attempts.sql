-- Acceptance tokens reserve nothing. One bounded current token per owned basket.
CREATE TABLE web_checkout_quotes (
    customer_id bigint PRIMARY KEY REFERENCES customers(id) ON DELETE CASCADE,
    cart_version bigint NOT NULL,
    token_hash varchar(64) NOT NULL,
    quote_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL
);

-- Immutable request/fulfilment/price binding; dispatch state is deliberately separate
-- from actual payment truth, which remains in payments + orders.
CREATE TABLE web_checkout_attempts (
    id varchar(64) PRIMARY KEY,
    customer_id bigint NOT NULL REFERENCES customers(id),
    order_id bigint NOT NULL UNIQUE REFERENCES orders(id),
    idempotency_key varchar(80) NOT NULL,
    request_hash varchar(64) NOT NULL,
    cart_version bigint NOT NULL,
    quote_token_hash varchar(64) NOT NULL,
    quote_hash varchar(64) NOT NULL,
    snapshot jsonb NOT NULL,
    state varchar(20) NOT NULL CHECK (state IN ('READY','CREATING','PAYABLE','UNKNOWN')),
    provider_reference varchar(64) NOT NULL UNIQUE,
    provider_link_id varchar(100) UNIQUE,
    provider_url varchar(255),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(customer_id,idempotency_key)
);
CREATE INDEX web_checkout_attempts_customer ON web_checkout_attempts(customer_id);