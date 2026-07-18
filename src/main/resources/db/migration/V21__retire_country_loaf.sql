-- V21__retire_country_loaf.sql
-- Country Loaf is being discontinued and has been removed from the website and
-- from menu.json. MenuSyncService only upserts items present in menu.json and
-- never deactivates removed ones, so without this migration the Country Loaf
-- row would stay active in the live DB and keep showing in the WhatsApp menu.
--
-- Deactivate rather than delete so existing order history
-- (order_items -> menu_items) stays intact.
UPDATE menu_items SET active = FALSE WHERE name = 'Country Loaf';
