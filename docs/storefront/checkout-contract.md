# T006 backend contract — durable retail checkout

## Replacement checkout contract (2026-10-26, local backend validation complete)

**Current status:** durable cart + transactional retail checkout implemented and
locally validated, default-off. Final system Java-17 Maven `-B -q clean test`:
**348 tests / 45 suites, 0 failures, 0 errors, 0 skipped**, real local PostgreSQL
18.3, 49 Flyway migrations validated, mocked provider/WhatsApp boundaries. No
frontend, parked implementation, bundle, push, deploy or live provider work.
This is backend pilot readiness only, not rollout approval or a full T006
frontend/end-to-end sign-off. The JSON contract below did not change in this pass.

### Security / integration review result

Reviewed source and tracked diff before changes: cookie-only ownership, exact
Origin/CSRF/no-store gates, fresh quote and fulfillment binding, global
capacity-before-customer lock ordering, rollback boundaries, WEB/WA separation,
single durable dispatch, strict hosted URL allowlist, signed amount/currency/link
and reference binding, webhook-before-persistence, cutoff/expiry and notification
commit ordering. The obsolete cart-503 assertions, bounded test pools, hostless
URL rejection and post-commit notification fixes were already present.

New regressions exposed repeated provider cancellation after successful revocation.
Fixed by using the uncleared payment URL as the retry marker while retaining IDs
for reconciliation: successful revocation is not repeated by checkout/status;
failure retains the URL for a later revoke attempt. No payment URL is exposed for
expired/cancelled attempts, and late money cannot resurrect the order.

Key green suites: WebCheckoutHttpTest 38, WebCartHttpTest 8,
WebCheckoutDisabledTest 1, CapacityBoundaryTest 18, WebWebhookSafetyTest 16,
CutoffTransactionSafetyTest 17. These include real concurrent web/WA capacity
contention, acceptance rollback, provider-persistence rollback to UNKNOWN, failed
revoke retry, cancellation during dispatch and 14 unsafe URL forms. See
checkout-progress-details.md for the exact execution/failure history and limits.

Not validated here: live Razorpay/OTP/WhatsApp, production network or configuration,
browser UX, physical process kill or acknowledged-commit/network-loss chaos, load
or penetration testing. Notifications are post-commit, not a durable outbox; no
exactly-once delivery guarantee. Credit-positive/zero-payable support gates and
manual UNKNOWN reconciliation remain deliberate pilot limitations.

This section supersedes the historical core-only checkout boundary below. Backend
only; the independent checkout flag remains default false. Existing cookie-only
identity, exact Origin, CSRF and private/no-store rules apply to every route.

* POST `/api/checkout/prepare`, JSON `{cartVersion:1}` only: 200
  `{cartVersion,quote,quoteToken,expiresAt}`. Current owned basket must be valid.
  An opaque random acceptance token is stored hashed, customer/version/complete
  quote-bound, expires after 10 minutes; no capacity/order/payment reservation.
* POST `/api/checkout`, exact JSON fields
  `{idempotencyKey,cartVersion,quoteToken,name,address,deliveryPreference,loafPreference}`.
  Key: 16..80 ASCII letters/digits/underscore/hyphen; version: positive safe JSON
  integer; quoteToken: opaque token from prepare; name: trimmed 1..100 characters;
  address: trimmed 10..2000 characters; no control characters. Preferences are
  exactly GATE/DOOR/IN_PERSON and SLICED/WHOLE. All seven fields required, unknown
  fields/coercions rejected with 400 INVALID_REQUEST. Delivery only, INR retail.
  Name/address/preferences are immutable checkout snapshots; address never
  overwrites the customer profile. Response 200 `Checkout` (not a redirect).
* GET `/api/checkout/{opaqueId}`: 200 owned `Checkout`, otherwise uniform 404
  CHECKOUT_NOT_FOUND. No order/customer/provider IDs or contact/address echoed.
* `Checkout`: `{id,status,paymentUrl,expiresAt}`. Status is CREATING, PAYABLE,
  UNKNOWN, PAID, REVIEW_REQUIRED, CANCELLED or EXPIRED. Only PAYABLE may include
  a non-null HTTPS paymentUrl on exact host rzp.io or razorpay.com; no credentials,
  unusual ports or fragments. Poll CREATING/UNKNOWN; neither polling nor retries
  create another link. UNKNOWN after ambiguous dispatch/crash requires support.
  PAID derives solely from the existing verified Payment and actual live order,
  never the browser return URL. Expiry/cancellation suppresses all payment URLs.

Customer+key is durable unique. Exact retransmission returns the original owned
attempt even after cart edits or token expiry; changed version/token/fulfillment
with the same key returns 409 IDEMPOTENCY_CONFLICT. New attempt requires matching
current version, unexpired token and exact fresh quote hash, otherwise 409
CART_CONFLICT / QUOTE_CHANGED / QUOTE_EXPIRED. New concurrent active attempts for
the same customer require review (409 CHECKOUT_IN_PROGRESS); uncertain attempts
are never automatically retried/replaced. CreditBalance>0 OR creditApplied>0
returns 409 CREDIT_SUPPORT_REQUIRED, zero-payable returns 409
ZERO_PAYABLE_SUPPORT_REQUIRED. Quotes retain credit; none is consumed/reserved.
Gateway not configured as Razorpay with webhook verification returns 503
PAYMENT_UNAVAILABLE (no QR fallback). Invalid/currently unavailable baskets reject
without creating an attempt/order/payment. Capacity failure: 409 FULLY_BOOKED.

Acceptance creates an independent WEB PENDING_CONFIRMATION retail order and
RAZORPAY PENDING payment atomically under customer + shared database capacity
serialization. Acceptance snapshots complete quote, name/address/preferences and
delivery date; legacy WhatsApp merging must not mutate a WEB attempt order.
Only a committed durable READY-to-CREATING claim may call Razorpay, outside a DB
transaction, once, stable attempt reference and ORDER/orderId notes plus attempt
binding. Exception/timeout/invalid provider response becomes UNKNOWN, never a
second link. Stored provider ID/URL is atomic. Signed webhook-before-persistence
uses the attempt reference/notes to bind the link without weakening amount,
currency, payment-id, signature or link checks. Cutoff-cancelled or expired orders
are not resurrected by late money; manual review is required.

### Local failure semantics / pilot restrictions

Database lock/transaction failures return 503 `UNAVAILABLE` with the existing safe
problem envelope. The failed transaction is rolled back in full; no provider call
may follow failed acceptance. A provider-response persistence failure leaves the
committed attempt UNKNOWN (or CREATING until aged to UNKNOWN if reconciliation
storage itself is unavailable). Replay/polling never retries create. A signed,
fully bound webhook can reconcile UNKNOWN before expiry. The initial expiration
read may return EXPIRED while performing cancellation; subsequent reads may return
CANCELLED. Both suppress payment URLs. Browser return query parameters are ignored.

The credit-positive/zero-payable gates are deliberate pilot restrictions, not
completed credit reservation support. No WEB attempt reserves or consumes credit.
UNKNOWN needs support reconciliation; no operator reconciliation UI or automatic
retry is provided. Commercial/prepaid-subscription manual capacity overrides retain
their existing policy and serialize against retail; they are not newly capped.

### Historical execution reference / research before source changes

Continuation research: actual acceptance/dispatch now exists in WebCheckoutService
and V49, with 33 checkout HTTP regressions plus shared-capacity/webhook/cutoff
tests already written. The last full run failed for two obsolete cart assertions
(still expecting the old 503 stub) and PostgreSQL connection exhaustion from
cached test application contexts, not an excuse to restore the stub. This bounded
integration pass will update only those obsolete assertions, bound test pools,
test malformed hostless provider URLs before fixing URL validation, and defer
gateway settlement notifications until after commit/release of capacity. No new
scenario/task.md/skill root was supplied; no workflow artifacts invented. Prior
non-atomic implementation boundaries are recorded in progress; this remaining
integration/validation boundary is atomic. Focused red/green then full clean suite
use the configured local PostgreSQL and mocks, never Docker/live providers.

Reviewed current cart/catalog/session, OrderService/Order/CartPricingService,
RazorpayService/webhook/AdminService and capacity research. V48 is latest current
migration. Existing confirm does not recheck capacity and legacy merge can mutate
pending orders: shared transaction advisory locking and WEB immutability needed.
Admin webhook currently overwrites link ID without matching: add WEB-bound guard,
keeping existing legacy route. Payment creation must use actual INR total (never
legacy payment-test-mode random amounts). No scenario/task.md/assessment/skill
root supplied; none invented. Skill recovery found none; Execution extensions
none. Decomposition assessment: non-atomic boundaries executed in dependency
order: shared capacity, acceptance/attempt, atomic order/payment, durable dispatch,
webhook/cutoff integration, final tests. Local PostgreSQL, mocked providers only;
tests before implementation, focused then full system Java-17 Maven clean test.

---

## Historical durable-cart core contract (checkout superseded above)

Everything below records the earlier cart-only milestone, NOT the current backend
status. In particular its 503 checkout stub and unimplemented-branch statements are
superseded by the replacement contract and final validation summary above/below.

Status: **partial T006, not a payment/checkout implementation**. This contract is
written before source changes. Parent must not expose a Pay/Place order action.
No frontend routing, rollout, bundles, WhatsApp mutation or provider changes here.

## Access and transport

All routes below require both `bakery.web.enabled=true` and the separate
`bakery.web.checkout.enabled=true` (`BAKERY_WEB_CHECKOUT_ENABLED`, default false).
OTP sending remains independently gated. When off: 404. When on: existing opaque
browser/session cookies are the only customer identity; missing/expired cookies
give 401 even for an admin principal or claimed customer headers. Existing exact
origin checks, credentialed CORS and `Cache-Control: no-store, private` apply.
Bootstrap CSRF with GET `/api/session`; send cookies and `X-XSRF-TOKEN` on PUT/POST.
CSRF failures are 403, before controller/auth processing. No new headers needed.

## Implemented scope / exact JSON

* GET `/api/cart`: 200 `Cart`; does not create a cart. No query identity accepted.
* PUT `/api/cart`: replace entire owned cart, 200 `Cart`. Only request fields:
  `{version:0,lines:[{itemId:123,quantity:2}],date:"2026-11-01"}`.
  `version` is required, integral 0..9007199254740990. Empty cart starts at version
  0; each successful PUT increments it. A mismatch returns 409 `CART_CONFLICT`;
  re-GET and explicitly resolve edits, never blindly overwrite. Retransmitting a
  successful PUT with its old version conflicts (PUT is not checkout idempotency).
  `date` may be omitted/null; otherwise exact yyyy-MM-dd, no later than 60 days
  from today in Asia/Kolkata. IDs and quantities use the current quote parser:
  max 40 input lines, positive integral IDs, integral quantities 1..99, duplicate
  IDs merged up to 99. Unknown fields/coercions/retired products/bad dates fail 400.
  Invalid input never changes stored version or lines.

`Cart` is `{version,lines:[{itemId,quantity}],date,quote,problems:[{code,message}]}`.
`quote` is the existing `/api/quote` DTO, repriced freshly with the authenticated
customer (including F&F and credit); not a stored/accepted quote or reservation.
`problems` mirrors quote problems. On GET, a stored basket made unavailable by
catalogue/day/lead-time changes remains readable: `quote:null` and safe problems.
An empty/non-dated basket may be saved with advisory EMPTY_CART/DATE_REQUIRED;
capacity problems are advisory and disclose no capacity counts. Cart contains no
customer/order IDs, phone, name or address. Money is server-computed INR only.
GET can change quote without changing version: version binds edits, NOT prices.

## Explicit fail-closed checkout boundary

* POST `/api/checkout`: with enabled flags, valid cookies and CSRF, always 503
  `CHECKOUT_UNAVAILABLE`. No request DTO is accepted yet; body/idempotency key is
  never treated as an accepted order. No attempts, orders, payments, reservations,
  credit consumption or provider calls are made. Retrying remains side-effect-free.
* GET `/api/checkout/{opaqueId}`: with enabled flags and valid cookies, always 404
  `CHECKOUT_NOT_FOUND`. No checkout records exist in this core. Never reveals IDs
  or ownership or treats a redirect as payment confirmation.

Application errors use existing `{valid:false,problems:[{code,message}]}` shape;
no submitted input or exception details are echoed. Framework security failures
may have empty bodies. Clients must key off HTTP status, not assume JSON on 403.

## Research / remaining T006 gates

Current Java 17 / Spring Boot 3.5 code already provides `CartPricingService`,
`StorefrontCatalogService`, opaque customer sessions and signed Razorpay webhooks.
Session resolution locks the customer row. In this core it joins the cart service
transaction, retaining the lock through save; the durable version compare-and-swap
also explicitly guards lost edits and concurrent first-cart creation.
Existing WhatsApp capacity-affecting writes have no shared capacity exclusion;
credit is deducted on confirmation without unpaid-order reservations. A web-only
lock/reservation would not fix those races. Provider timeout/cancellation/late-paid
handling must be designed together, not approximated with retries or QR fallback.

Decomposition review: full T006 is non-atomic. This coherent first boundary is
durable owned carts with optimistic concurrency and advisory quotes only. Follow-on
work must implement cross-channel transactional capacity protection, accepted quote
and fulfillment/name/address/loaf snapshot, durable customer/key unique attempts,
outside-transaction Razorpay dispatch with UNKNOWN reconciliation, HTTPS link
allowlisting, webhook/cutoff cancellation safety and credit-bearing support gating
or reliable cross-channel reservations. Zero-payable orders require review, never
fake paid. **None of those order/payment branches is implemented here.**

Validation plan: existing local PostgreSQL (no Docker), mocked WhatsApp/Razorpay,
tests first; real loopback HTTP on all four routes, cookie/CSRF/admin isolation,
disabled flag regression, strict bounds, stale data, persistence and competing
same-version saves. Then complete Java `clean test`. No provider/network/real OTP.