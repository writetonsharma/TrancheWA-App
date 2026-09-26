-- Whether order-status changes send WhatsApp updates to the customer. Retail defaults TRUE; commercial (esp. bulk)
-- can be silenced since payment often lands after delivery, making "in baking" messages misleading.
ALTER TABLE orders ADD COLUMN notify_customer BOOLEAN NOT NULL DEFAULT TRUE;
