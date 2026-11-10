-- V55__offer_thresholds_bump.sql
-- Raise the free-delivery and free-roll thresholds. Brownies carry a much thinner
-- margin than bread, and at the old thresholds a single discounted pack earned both
-- perks. Thresholds are measured on the DISCOUNTED subtotal, so 800 = 1000 of list
-- price while the 20% launch discount is live.
-- The offer codes keep their original names; renaming them would orphan the rows the
-- admin Offers page and PromotionFlowTest look up by code.
UPDATE offers
SET threshold_amount = 800.00,
    label = 'Free delivery over 800',
    updated_at = now()
WHERE code = 'free-delivery-550';

UPDATE offers
SET threshold_amount = 600.00,
    label = 'Free roll of the day over 600',
    updated_at = now()
WHERE code = 'sweet-roll-450';
