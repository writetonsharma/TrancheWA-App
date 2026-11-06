-- Categories that exist only for admin billing (e.g. gift hampers): never shown to retail
-- customers, never baked, never counted against the day's retail capacity.
ALTER TABLE menu_categories ADD COLUMN retail_visible BOOLEAN NOT NULL DEFAULT TRUE;
