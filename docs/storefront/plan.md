# Website ordering implementation plan

## Shopping snapshot execution reference (2026-10-27)

- Backend-only additive POST /api/shopping accepts strictly an object containing only optional date (ISO date or null). Optional verified session follows /api/quote; ApiGuard, CSRF, Origin and private/no-store apply. Existing catalog/quote contracts remain unchanged.
- Confirmed sources: StorefrontController/StorefrontCatalogService, WebApiSecurity.CustomerIdentity, CartPricingService and DeliveryRules. Shared promotions choose the cheaper WHOLE-basket override/public rate and round discounts to whole rupees. Batch demand depends on date and quantity. Therefore multiplying single-item prices is an estimate, not exact basket pricing; do not export customer override maps, credit or demand.
- Snapshot: seven upcoming base dates, including full dates; date query restricted to that finite set. Each date exposes only eligible active item IDs and remaining retail allowance clamped 0..5. Item unitPrice is shared single-item quantity-one price BEFORE batch, fee or credit; optional dated batchDiscount is explicitly quantity-one only. No basket grand total promised.
- WEB enforcement points: WebCartService.put after shared quote parsing/duplicate merging; WebCheckoutService.validQuote for prepare/new acceptance, which runs AFTER existing idempotency lookup. Stored oversized carts remain readable; reject every save above five, allow reduction to five or fewer. No schema/WA/subscription/pricing changes.
- Decomposition verdict: atomic, one bounded shopping contract with its WEB retail guardrail. No scenario skill root, assessment.md or task.md was supplied; this existing plan is the execution reference. Skill lookup returned none; Execution extensions returned none. Source was inspected directly, not inferred from historical notes.
- Validation: focused standalone pricing/snapshot and security/checkout tests, then full clean Maven with prescribed JDK/Maven. No live OTP/order/payment, push, frontend or bundles.

Status: implementation started 2026-10-25. Baseline: 161 Java tests passed against configured local PostgreSQL test database. Independent of parked work.

| Task | Requirement mapping | Status |
|---|---|---|
| T001 Record scope, security contracts and production guardrails | REQ-001–009 | Complete |
| T002 Implement durable WhatsApp OTP and customer session foundation with tests | REQ-002,003,009 | Locally validated; hosted live delivery pending |
| T003 Implement bounded catalogue/quote API and shared authoritative pricing with regression tests | REQ-004,009 | Locally validated |
| T004 Add opt-in accessible preview cart and login UI without altering default public workflow | REQ-002,003,005 | Locally validated; exact production HTTPS origins enabled for approved live test |
| T005 Validate batch, security review and document Meta/deployment prerequisites | REQ-003,004,005,009 | Local checks complete; hosted live test pending user deployment |
| T006 Persist website baskets and build transactional/idempotent retail checkout | REQ-006 | Deployed; user reports real Razorpay payment and server-verified paid status; admin/notification acceptance still pending |
| T007 Customer history, receipts, reorder and saved preferences | REQ-006 | Profile/defaults and website order history implemented locally with automatic basket/status UX; 398 backend / 132 frontend tests passed. Dedicated receipts and reorder remain future scope; account deployment pending |
| T008 Website subscription signup, schedule, payment and renewal | REQ-007 | Not started |
| T009 Guard all WhatsApp commerce mutations and route actions to website | REQ-001,008 | Not started; blocked on website pilot |
| T010 Staging end-to-end payment/security/accessibility tests and controlled launch | REQ-001–009 | Not started |

No remote push, deployment or live Authentication message is performed from this machine. The user approved live preview/login testing after they deploy on 2026-10-26; this supersedes the earlier staging-only restriction for T002–005, not later checkout/payment gates. Future tasks remain visible rather than being misrepresented as complete.

## Backend milestone evidence (2026-10-25)

- Clean Maven regression: 249 tests, zero failures/errors/skips. Subsequently added real loopback Tomcat HTTP smoke: one test passed, covering every foundation route with mocked provider delivery (not a live Meta test).
- Verified customer cookies now supply quote identity; admin principals and claimed customer headers do not. Login renews browser binding; login/logout rotate CSRF.
- V46 adds durable OTP/session/throttle storage; V47 records dedicated web activity so cleanup preserves active web-only customers without changing WhatsApp inbound windows or consent.
- Approved template reported Active by user: website_login_otp, en, Copy Code, 5-minute expiry/message validity. Configuration defaults recorded; API and sending flags remain false.
- Follow-up read-only security review found no concrete remaining local blockers. Vercel/Railway HTTPS/proxy/cookie/client-IP trust remains a launch gate.
- Nothing pushed/deployed. Transfers use byte-verified root base64 bundles; user unpacks and pushes elsewhere before explicit sync/verification here.

## Opt-in frontend milestone evidence (2026-10-26)

- Combined clean backend regression now passes 250 tests, zero failures/errors/skips, including the real loopback HTTP smoke with mocked provider delivery.
- Website enhancement adds exact catalogue-bound quantity controls, an animated olive right-side native-dialog basket, server quote previews and WhatsApp OTP login/logout. Existing public content, prices, photography and WhatsApp ordering links are unchanged; config remains false.
- Parent review corrected the CSRF contract to X-XSRF-TOKEN, added labelled polite quantity announcements and explicit Escape handling. Login/logout consume replacement CSRF tokens.
- Node model/API/view checks: 24 tests passed. Syntax/whitespace checks pass. Browser checks against a fake loopback API cover disabled zero API/DOM activity; all 20 mappings; basket persistence; date/quote; fake challenge/verify/logout with token rotation and personalised credit; stale-total clearing on logout/offline; Escape/backdrop/focus restoration; Tab wrapping; 1440px desktop and 390/320px mobile layouts with no horizontal overflow; reduced motion.
- Browser fixture amounts are deliberately fake (not product pricing). No real OTP or payment was sent. Browser + hosted backend integration, real device screen-reader/zoom coverage, CDN/proxy HTTPS/cookie/IP validation and real delivery remain staging/launch gates.
- Checkout, history, subscriptions and WhatsApp commerce cutover are not implemented by this milestone.

## Production connection follow-up (2026-10-26)

- Per explicit user request, enabled the frontend only on exact HTTPS www/apex
	bakery origins. Added Vercel host-guarded rewrites for six existing customer
	endpoints to the existing Railway service; no admin/webhook/payment wildcard.
- Explicit browser/CDN no-store, rewrite-cache off and storefront asset no-store.
	Preview/local hosts remain disconnected. Backend flags still default false.
- Added five activation/routing/cache tests: 29 total frontend tests pass, plus
	syntax/whitespace checks. Repeated clean Maven: 250 tests, zero failures/errors/
	skips. Fresh local browser confirms zero API requests while off; test injection
	confirms fake quote/login/logout and personalised-credit clearing still work.
- The website storefront/DEPLOYMENT.md records exact Railway settings, private
	secret handling, deployment order, manual cookie/Origin/cache/OTP checks and
	rollback. User reports the Authentication template Active; actual live delivery
	and hosted proxy behaviour remain unverified. No push/deploy/real OTP performed.