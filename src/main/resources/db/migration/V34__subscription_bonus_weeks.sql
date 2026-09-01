-- Free bonus delivery weeks on a subscription (paid weeks = commitment_weeks; total deliveries = both).
ALTER TABLE subscriptions ADD COLUMN bonus_weeks INT NOT NULL DEFAULT 0;
