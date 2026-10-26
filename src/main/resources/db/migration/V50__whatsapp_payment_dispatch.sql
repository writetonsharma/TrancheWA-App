-- Dispatch ownership is not payment truth. Keep it even when no payment row exists:
-- a timeout/crash can leave a live provider link whose id was never received.
CREATE TABLE whatsapp_payment_dispatches (
    order_id bigint PRIMARY KEY REFERENCES orders(id) ON DELETE CASCADE,
    provider_reference varchar(64) NOT NULL UNIQUE,
    state varchar(20) NOT NULL CHECK (state IN ('CREATING', 'COMPLETE', 'UNKNOWN')),
    updated_at timestamptz NOT NULL DEFAULT now()
);
-- CREATING/UNKNOWN have deliberately no lease or automatic retry. Reconciliation
-- must prove the previous provider outcome before releasing this per-order fence.