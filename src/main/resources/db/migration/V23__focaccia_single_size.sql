-- Consolidate focaccia Full/Half into single 8x8" tray at ₹420
UPDATE menu_item
SET name        = 'Olive, Tomato & Rosemary Focaccia',
    price       = 420.00,
    description = '8x8 inch tray; Fri-Sun only',
    list_title  = 'Tomato Focaccia'
WHERE name = 'Olive, Tomato & Rosemary Focaccia (Full)';

UPDATE menu_item SET active = false
WHERE name = 'Olive, Tomato & Rosemary Focaccia (Half)';
