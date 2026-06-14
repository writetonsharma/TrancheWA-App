ALTER TABLE orders
    ADD COLUMN delivery_address TEXT,
    ADD COLUMN location_lat     DECIMAL(9,6),
    ADD COLUMN location_lng     DECIMAL(9,6);
