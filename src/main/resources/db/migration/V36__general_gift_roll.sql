-- V36__general_gift_roll.sql
-- Broaden the complimentary gift from a sweet roll to any roll (sweet or savory) of the day.
-- Eligibility (excluding F&F bespoke-rate customers) is enforced in PromotionEngine.
UPDATE offers
SET gift_label = 'Complimentary sweet or savory roll of the day',
    label = 'Free roll of the day over 450',
    updated_at = now()
WHERE code = 'sweet-roll-450';
