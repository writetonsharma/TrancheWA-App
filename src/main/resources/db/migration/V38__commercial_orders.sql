-- Commercial / bulk orders created from the admin dashboard.
-- source distinguishes them from retail (WhatsApp) orders; invoice_number holds the TRB-INV-... bill number.
ALTER TABLE orders ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'RETAIL';
ALTER TABLE orders ADD COLUMN invoice_number VARCHAR(30);
ALTER TABLE orders ADD COLUMN business_name VARCHAR(150);
ALTER TABLE orders ADD CONSTRAINT uq_orders_invoice_number UNIQUE (invoice_number);
