-- V1__initial_schema.sql

CREATE TABLE customers (
    id          BIGSERIAL PRIMARY KEY,
    phone       VARCHAR(20) NOT NULL UNIQUE,
    name        VARCHAR(100),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE menu_categories (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    display_order INT NOT NULL DEFAULT 0,
    active      BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE menu_items (
    id          BIGSERIAL PRIMARY KEY,
    category_id BIGINT NOT NULL REFERENCES menu_categories(id),
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    price       NUMERIC(10, 2) NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    active      BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE whatsapp_conversations (
    id              BIGSERIAL PRIMARY KEY,
    customer_id     BIGINT NOT NULL REFERENCES customers(id),
    state           VARCHAR(50) NOT NULL DEFAULT 'IDLE',
    context         JSONB,
    started_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE orders (
    id                  BIGSERIAL PRIMARY KEY,
    customer_id         BIGINT NOT NULL REFERENCES customers(id),
    conversation_id     BIGINT REFERENCES whatsapp_conversations(id),
    status              VARCHAR(50) NOT NULL DEFAULT 'draft',
    fulfillment_type    VARCHAR(20) NOT NULL DEFAULT 'pickup',
    total_amount        NUMERIC(10, 2),
    cutoff_warned       BOOLEAN NOT NULL DEFAULT FALSE,
    notes               TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE order_items (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT NOT NULL REFERENCES orders(id),
    menu_item_id BIGINT NOT NULL REFERENCES menu_items(id),
    quantity    INT NOT NULL,
    unit_price  NUMERIC(10, 2) NOT NULL,
    subtotal    NUMERIC(10, 2) NOT NULL
);

CREATE TABLE payments (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT NOT NULL REFERENCES orders(id),
    upi_id      VARCHAR(100),
    amount      NUMERIC(10, 2),
    status      VARCHAR(50) NOT NULL DEFAULT 'pending',
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE payment_screenshots (
    id                  BIGSERIAL PRIMARY KEY,
    payment_id          BIGINT NOT NULL REFERENCES payments(id),
    whatsapp_media_id   VARCHAR(255),
    ocr_raw             TEXT,
    ocr_amount          NUMERIC(10, 2),
    ocr_upi_id          VARCHAR(100),
    ocr_status          VARCHAR(50),
    ocr_transaction_ref VARCHAR(100),
    ocr_confidence      VARCHAR(20),
    review_notes        TEXT,
    received_at         TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE feedback (
    id          BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES customers(id),
    message     TEXT NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Seed menu categories
INSERT INTO menu_categories (name, display_order) VALUES
    ('Loaves', 1),
    ('Rolls', 2),
    ('Pastries', 3),
    ('Specials', 4);
