-- Where the order was raised. Carts still key on customer + delivery date, never on channel,
-- so a WhatsApp order and a web order continue to merge into one order for the same day.
ALTER TABLE orders ADD COLUMN channel VARCHAR(20) NOT NULL DEFAULT 'WHATSAPP';

-- Reserved for optimistic locking once the web channel can WRITE to orders (Phase 1).
-- Intentionally NOT mapped as @Version on the entity yet: nothing in the app handles
-- ObjectOptimisticLockingFailureException, so turning it on today would convert a rare silent
-- lost update into a hard failure mid-conversation. The column ships now so enabling it later
-- is an entity change with no migration.
ALTER TABLE orders ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
