-- Store the hosted link URL so a repeat prompt re-sends the same link instead of minting a second one.
ALTER TABLE payments ADD COLUMN gateway_link_url VARCHAR(255);
ALTER TABLE subscriptions ADD COLUMN gateway_link_url VARCHAR(255);
