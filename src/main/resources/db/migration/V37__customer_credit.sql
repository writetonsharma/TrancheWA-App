-- Customer credit balance (overpayment / goodwill), auto-applied to the next bill.
ALTER TABLE customers ADD COLUMN credit_balance DECIMAL(10,2) NOT NULL DEFAULT 0;

-- How much credit was applied to a given one-time order / subscription (snapshot for the bill line
-- and the amount to deduct from the customer's balance once payment is approved / activated).
ALTER TABLE orders ADD COLUMN credit_applied DECIMAL(10,2) NOT NULL DEFAULT 0;
ALTER TABLE subscriptions ADD COLUMN credit_applied DECIMAL(10,2) NOT NULL DEFAULT 0;
