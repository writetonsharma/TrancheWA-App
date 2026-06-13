-- V4__one_draft_per_customer.sql
-- Enforce at DB level: a customer can only have one DRAFT order at a time
CREATE UNIQUE INDEX idx_one_draft_per_customer ON orders (customer_id) WHERE status = 'DRAFT';
