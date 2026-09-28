-- Gateway (Razorpay) references so a paid webhook can be reconciled against a local record, and
-- the amount we actually asked for so the webhook can verify what was paid before confirming.
ALTER TABLE payments ADD COLUMN provider VARCHAR(20);
ALTER TABLE payments ADD COLUMN gateway_link_id VARCHAR(100);
ALTER TABLE payments ADD COLUMN gateway_payment_id VARCHAR(100);
CREATE INDEX idx_payments_gateway_link_id ON payments (gateway_link_id);

ALTER TABLE subscriptions ADD COLUMN gateway_link_id VARCHAR(100);
ALTER TABLE subscriptions ADD COLUMN gateway_payment_id VARCHAR(100);
-- Charged amount can differ from upfront_amount under payment test mode (token ~Rs 1 charge).
ALTER TABLE subscriptions ADD COLUMN gateway_charged_amount DECIMAL(10,2);
