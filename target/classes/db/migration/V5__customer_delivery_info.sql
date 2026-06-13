ALTER TABLE customers ADD COLUMN delivery_area    VARCHAR(100);
ALTER TABLE customers ADD COLUMN delivery_address TEXT;
ALTER TABLE customers ADD COLUMN location_lat     DECIMAL(9,6);
ALTER TABLE customers ADD COLUMN location_lng     DECIMAL(9,6);
