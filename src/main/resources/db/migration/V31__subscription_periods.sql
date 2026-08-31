-- One row per generated weekly delivery; the unique (subscription, week) prevents double-generation.
CREATE TABLE subscription_periods (
    id              BIGSERIAL PRIMARY KEY,
    subscription_id BIGINT    NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
    week_number     INT       NOT NULL,
    delivery_date   DATE      NOT NULL,
    order_id        BIGINT,
    created_at      TIMESTAMP NOT NULL,
    CONSTRAINT uq_subscription_week UNIQUE (subscription_id, week_number)
);
