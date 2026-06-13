-- V3__rename_rosemary_focaccia.sql
UPDATE menu_items SET name = 'Rosemary Focaccia' WHERE name = 'Rosemary Sea Salt Focaccia';
DELETE FROM menu_items WHERE name = 'Rosemary Focaccia' AND id NOT IN (
    SELECT MIN(id) FROM menu_items WHERE name = 'Rosemary Focaccia'
);
