-- V20__order_promo_columns.sql
-- Persist the promotion breakdown on each order so the WhatsApp summary and admin
-- views can show exactly what applied without recomputing.

ALTER TABLE orders ADD COLUMN IF NOT EXISTS discount_amount NUMERIC(10,2) NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS discount_label  VARCHAR(100);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS gift_label      VARCHAR(200);
