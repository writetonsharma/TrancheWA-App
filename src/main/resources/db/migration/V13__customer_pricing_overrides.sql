ALTER TABLE customers
    ADD COLUMN pricing_override DECIMAL(10, 2),
    ADD COLUMN free_delivery BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN override_expires_at TIMESTAMP,
    ADD COLUMN override_note TEXT;
