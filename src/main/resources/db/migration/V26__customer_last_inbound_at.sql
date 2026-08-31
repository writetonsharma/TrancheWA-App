-- Anchor for WhatsApp's 24h customer-service window: the last time a customer messaged us.
ALTER TABLE customers ADD COLUMN last_inbound_at TIMESTAMP;
