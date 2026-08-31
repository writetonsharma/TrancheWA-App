-- Marks a customer as eligible for the self-serve subscription flow (part of the F&F list).
ALTER TABLE customers ADD COLUMN subscription_eligible BOOLEAN NOT NULL DEFAULT FALSE;
