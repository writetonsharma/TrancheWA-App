-- Per-category flat pricing for Friends & Family customers.
-- A customer may have a flat per-unit price for one or more menu categories; a
-- category price wins over the single all-items pricing_override, and any category
-- left unset falls back to the item's normal list price.
CREATE TABLE customer_category_prices (
    customer_id BIGINT       NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    category    VARCHAR(255) NOT NULL,
    flat_price  DECIMAL(10, 2) NOT NULL,
    PRIMARY KEY (customer_id, category)
);
