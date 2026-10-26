-- Separate from WhatsApp inbound-window and marketing-consent state.
ALTER TABLE customers ADD COLUMN last_web_activity_at timestamp;

-- Preserve activity already recorded before this migration, including returning web-only users.
-- V46 session timestamps are timestamptz; customer timestamps use the application's IST zone.
UPDATE customers c
SET last_web_activity_at = s.last_seen_at AT TIME ZONE 'Asia/Kolkata'
FROM (
    SELECT customer_id, MAX(last_seen_at) AS last_seen_at
    FROM web_customer_sessions
    GROUP BY customer_id
) s
WHERE c.id = s.customer_id;