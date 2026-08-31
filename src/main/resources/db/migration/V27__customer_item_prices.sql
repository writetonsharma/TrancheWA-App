-- Per-item flat pricing for Friends & Family customers.
-- A customer may have a flat per-unit price for one or more menu items (by item name);
-- an item price wins over the customer's category flat price and the all-items pricing_override,
-- and any item left unset falls back to its category price or normal list price.
CREATE TABLE customer_item_prices (
    customer_id BIGINT       NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    item_name   VARCHAR(255) NOT NULL,
    flat_price  DECIMAL(10, 2) NOT NULL,
    PRIMARY KEY (customer_id, item_name)
);
