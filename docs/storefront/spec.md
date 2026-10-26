# Website ordering — requirements

Approved direction: 2026-10-25. Architecture and estimates are independent of the parked September implementation.

- REQ-001: Website is the only eventual customer commerce-write interface. Existing production WhatsApp ordering stays enabled until complete website checkout/subscriptions pass pilot acceptance. WhatsApp then retains owned status, notifications, support, OTP and opt-out only.
- REQ-002: Anonymous browsing and device-local basket; verified normalized WhatsApp phone links existing customer identity. Never expose personal details or prices before authentication.
- REQ-003: WhatsApp Authentication-template OTP, short-lived single-use challenges, bounded attempts/resends, durable secure sessions, CSRF, exact-origin CORS, no secret/OTP logging or client storage. No SMS fallback. Login must not update WhatsApp inbound-window timestamps or subscribe customers to marketing.
- REQ-004: Server authoritative catalogue, promotions, quotes, availability, credit and checkout. No capacity numbers in public output. Retired products stay retired. No client-supplied amounts are accepted.
- REQ-005: Responsive accessible right-side cart drawer with quantity controls and device-local persistence, request ordering protection, safe offline behaviour and reduced motion.
- REQ-006: Durable website basket, transactional/idempotent checkout, hosted Razorpay payment links, gateway-verified paid state, customer-owned order history/details/receipts/reorder.
- REQ-007: Website subscriptions: audience-appropriate signup, upfront payment, schedule, history and renewal. Changes/pause/skip/refunds remain support-assisted. Future schedule must not depend solely on generated weekly Order rows.
- REQ-008: Safe WhatsApp read-only cutover including old button payloads, global commands, greeting, reminders and existing pending payments. Backend/admin/payment/scheduling writes continue.
- REQ-009: Existing admin, WhatsApp, subscriptions, pricing and payment regression tests remain green; no production data reset. On 2026-10-26 the user approved a bounded live-site preview/login test in place of separate staging; checkout, payments and WhatsApp cutover remain outside this exception.

First implementation batch: REQ-002–005 foundations, deployment-disabled by default. It is not a public checkout release. REQ-006–008 require subsequent batches; never imply a quoted basket is a submitted or reserved order.

Live-test follow-up (2026-10-26): frontend preview enables on exact HTTPS www/apex
bakery origins only; Vercel proxies six exact customer endpoints to Railway with
host restrictions and no-store. Backend environment flags remain explicit launch
controls. The user manually tests real OTP after transport verification; no local
automated test is permitted to send real messages.