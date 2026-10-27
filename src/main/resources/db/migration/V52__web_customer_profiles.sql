-- Website defaults only. Never replace the shared conversational/customer record.
CREATE TABLE web_customer_profiles (
    customer_id bigint PRIMARY KEY REFERENCES customers(id) ON DELETE CASCADE,
    version bigint NOT NULL CHECK (version BETWEEN 1 AND 9007199254740991),
    name varchar(100) NOT NULL,
    address varchar(2000) NOT NULL,
    delivery_preference varchar(20) NOT NULL CHECK (delivery_preference IN ('GATE','DOOR','IN_PERSON')),
    loaf_preference varchar(20) NOT NULL CHECK (loaf_preference IN ('SLICED','WHOLE')),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Stable bounded customer history/recovery without loading an unbounded collection.
CREATE INDEX web_checkout_attempts_history ON web_checkout_attempts(customer_id, created_at DESC, id DESC);