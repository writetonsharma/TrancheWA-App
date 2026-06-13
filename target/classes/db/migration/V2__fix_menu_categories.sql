-- V2__fix_menu_categories.sql
-- Remove the placeholder categories seeded in V1 so MenuSyncService can load correct ones from menu.json
DELETE FROM menu_categories;
