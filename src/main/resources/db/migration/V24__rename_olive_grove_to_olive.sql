-- V24__rename_olive_grove_to_olive.sql
-- Rename "Olive Grove Loaf" back to "Olive Loaf".
--
-- MenuSyncService matches menu.json by (category, name) and never renames rows,
-- so an explicit UPDATE is required (see V17). menu.json is updated to match;
-- the startup sync then refreshes price/order for the renamed row.

UPDATE menu_items SET name = 'Olive Loaf' WHERE name = 'Olive Grove Loaf';
