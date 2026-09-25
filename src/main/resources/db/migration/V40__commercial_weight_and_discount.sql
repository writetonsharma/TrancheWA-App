-- Product net-weight label shown on commercial bills (e.g. "350 g"); nullable, synced from menu.json.
ALTER TABLE menu_items ADD COLUMN weight_label VARCHAR(40);
-- Snapshot of the list price for a commercial line so the bill can show the per-item discount vs the charged price.
ALTER TABLE order_items ADD COLUMN list_unit_price DECIMAL(10,2);
