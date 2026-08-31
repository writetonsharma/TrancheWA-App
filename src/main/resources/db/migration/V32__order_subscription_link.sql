-- Tag orders generated from a subscription (billed ₹0, no payment due), and let order lines
-- carry a short note (e.g. "½ loaf") for the bake list.
ALTER TABLE orders ADD COLUMN subscription_id BIGINT REFERENCES subscriptions(id);
ALTER TABLE order_items ADD COLUMN note VARCHAR(40);
