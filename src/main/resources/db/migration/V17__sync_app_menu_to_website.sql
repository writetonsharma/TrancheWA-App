-- V17__sync_app_menu_to_website.sql
-- Sync the WhatsApp ordering menu to the website's finalized product catalog.
--
-- MenuSyncService upserts menu.json by (category, name) on startup: it updates
-- prices for matching names and inserts new names, but it never renames or
-- removes rows. So every RENAMED product needs an explicit UPDATE here to avoid
-- leaving the old-named row active as a duplicate. Prices are then refreshed
-- from menu.json by the startup sync; new items (focaccia Half) are inserted by it.

-- Loaves
UPDATE menu_items SET name = 'Classic Table White'  WHERE name = 'Everyday Sandwich Loaf';
UPDATE menu_items SET name = 'Multi-Seed Loaf'       WHERE name = 'Multi-Seeded Loaf';
UPDATE menu_items SET name = 'Milk & Butter Loaf'    WHERE name = 'Enriched White Loaf';
UPDATE menu_items SET name = 'Olive Grove Loaf'      WHERE name = 'Olive Loaf';

-- Buns & Rolls
UPDATE menu_items SET name = 'Multi-Seed Rolls'      WHERE name = 'Multi Seed Rolls';
UPDATE menu_items SET name = 'Bavarian Pretzel Buns' WHERE name = 'Pretzel Buns';

-- Breakfast & Specialty
-- The Olive & Tomato recipe already includes rosemary; it becomes the Full tray.
-- The Half tray is inserted by the startup menu sync from menu.json.
UPDATE menu_items SET name = 'Olive, Tomato & Rosemary Focaccia (Full)'
    WHERE name = 'Olive & Tomato Focaccia';
UPDATE menu_items SET name = 'Garlic & Herb Knots'  WHERE name = 'Garlic Knots';

-- Retire the standalone Rosemary Focaccia. Deactivate rather than delete so any
-- existing order history (order_items -> menu_items) stays intact.
UPDATE menu_items SET active = FALSE WHERE name = 'Rosemary Focaccia';

-- Sweet Bakes
UPDATE menu_items SET name = 'Chocolate Babka Buns' WHERE name = 'Chocolate Rolls';
UPDATE menu_items SET name = 'Nordic Cardamom Buns' WHERE name = 'Cardamom Buns';
