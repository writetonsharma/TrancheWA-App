-- Prepaid weekly subscriptions. Plan details are snapshotted here at signup so later
-- edits to subscriptions.json never change an in-flight subscription.
CREATE TABLE subscriptions (
    id               BIGSERIAL PRIMARY KEY,
    customer_id      BIGINT        NOT NULL REFERENCES customers(id),
    plan_code        VARCHAR(50)   NOT NULL,
    plan_name        VARCHAR(100)  NOT NULL,
    tier             VARCHAR(20)   NOT NULL,
    weekly_price     DECIMAL(10,2) NOT NULL,
    delivery_charge  DECIMAL(10,2) NOT NULL,
    commitment_weeks INT           NOT NULL,
    delivery_day     VARCHAR(10)   NOT NULL,
    start_date       DATE,
    end_date         DATE,
    status           VARCHAR(20)   NOT NULL,
    created_at       TIMESTAMP     NOT NULL,
    updated_at       TIMESTAMP     NOT NULL
);
