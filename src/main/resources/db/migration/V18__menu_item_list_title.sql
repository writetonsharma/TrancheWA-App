-- V18__menu_item_list_title.sql
-- WhatsApp interactive-list row titles are capped at 24 characters by the Cloud
-- API (error 131009). Some product names exceed that (e.g. the focaccia Full/Half
-- and the New York-Style bagel). Add an optional short list title used only for
-- WhatsApp list rows; the full name is still used everywhere else. Values are also
-- kept in sync from menu.json by MenuSyncService on every startup.

ALTER TABLE menu_items ADD COLUMN IF NOT EXISTS list_title VARCHAR(24);

UPDATE menu_items SET list_title = 'New York-Style Bagel'
    WHERE name = 'Classic New York-Style Bagel';

UPDATE menu_items SET list_title = 'Tomato Focaccia (Full)'
    WHERE name = 'Olive, Tomato & Rosemary Focaccia (Full)';

UPDATE menu_items SET list_title = 'Tomato Focaccia (Half)'
    WHERE name = 'Olive, Tomato & Rosemary Focaccia (Half)';
