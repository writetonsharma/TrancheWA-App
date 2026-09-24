-- Which seller identity issues a commercial invoice: the Tranché proprietorship (default) or Naveen Sharma as an individual.
ALTER TABLE orders ADD COLUMN seller_profile VARCHAR(20) NOT NULL DEFAULT 'COMPANY';
