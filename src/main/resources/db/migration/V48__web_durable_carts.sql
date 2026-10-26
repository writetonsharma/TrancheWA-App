-- Independent of conversational DRAFT orders. One bounded basket per customer.
CREATE TABLE web_carts (
    customer_id bigint PRIMARY KEY REFERENCES customers(id) ON DELETE CASCADE,
    version bigint NOT NULL CHECK (version BETWEEN 1 AND 9007199254740991),
    lines jsonb NOT NULL CHECK (jsonb_typeof(lines) = 'array' AND jsonb_array_length(lines) <= 40),
    delivery_date date,
    updated_at timestamptz NOT NULL DEFAULT now()
);