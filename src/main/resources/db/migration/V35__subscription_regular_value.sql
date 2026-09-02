-- Snapshot of the weekly bundle's à la carte value, for the subscription savings line.
ALTER TABLE subscriptions ADD COLUMN regular_value DECIMAL(10,2);
