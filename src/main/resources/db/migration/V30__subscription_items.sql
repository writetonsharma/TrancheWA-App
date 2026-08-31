-- The chosen weekly bundle, snapshotted by menu item name at signup.
CREATE TABLE subscription_items (
    id              BIGSERIAL PRIMARY KEY,
    subscription_id BIGINT      NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
    item_name       VARCHAR(100) NOT NULL,
    quantity        INT         NOT NULL,
    portion         VARCHAR(10) NOT NULL
);
