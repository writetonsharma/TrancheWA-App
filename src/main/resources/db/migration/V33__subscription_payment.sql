-- Subscription upfront amount + the customer's payment screenshot pending admin verification.
ALTER TABLE subscriptions ADD COLUMN upfront_amount DECIMAL(10,2);
ALTER TABLE subscriptions ADD COLUMN payment_screenshot_media_id VARCHAR(255);
