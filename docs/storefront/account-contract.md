# Website customer account API contract

## Account failure correction — 2026-10-28 (supersedes historical validation below)

Clean Java-17 Maven validation: **447 tests / 49 suites**, no failures/errors/skips.
Full sibling frontend validation: **340 tests**, no failures/skips; **26** source
and test JS/MJS syntax checks pass. No dependencies or migrations added.

Real loopback HTTP (embedded Tomcat + local test PostgreSQL, providers mocked)
created a checkout through cart/prepare/acceptance, exercised settlement with its
stored provider binding, and read PAYABLE then PAID detail/history. The exact
serialized response projections were exported under the ignored build output
and passed through the actual sibling frontend validators. `createdAt` was a
UTC string with microseconds, `expiresAt` a UTC string, dates strings and amounts
JSON numbers. No timestamp serializer defect was reproduced with current config.

A deliberately corrupted **synthetic test** historical quote returned HTTP 500
while the same authenticated profile still returned 200. Before the frontend fix,
the wire-contract regression failed with profile `null`: Promise.all tied profile
visibility to history success. Afterward profile loads alone; website history is
explicitly requested and fails independently. This proves the coupling, **not**
that the hosted customer's history is corrupt. No production account was read;
the hosted initiating fault, cookie/proxy behavior and browser acceptance remain
unobserved. Neither a cookie defect nor hosted Jackson defect is claimed.

Backend first is compatible with older strict clients: the profile keeps exactly
five fields and PUT validation/versioning/checkout prefill are unchanged. The new
location endpoint is separate; the new frontend tolerates its 404/error without
hiding the profile. No data migration, account merge or default mutation occurs.

Implementation started 2026-10-26. Final local validation: clean system-Maven
run passed **398 tests / 49 suites**, zero failures/errors/skips; website passed
**132 tests** and all **19 JS/MJS syntax checks**. Final scoped review PASS after
legacy-profile suggestion and two-tab checkout recovery corrections. Local browser
fixture checks cover account rendering, profile save, 462px/1280px overflow and
privacy clearing. This is not hosted acceptance or full accessibility coverage.
No live provider requests, push, deployment or parked implementation changes.

## Common security and transport rules

- All routes require both opaque website customer cookies (`TRANCHE_WEB_SESSION`
  and `TRANCHE_WEB_BROWSER`) with a valid, unexpired, non-idle stored session.
  Identity is never a request parameter/header, numeric ID, admin login, Basic
  credentials, Spring principal, or servlet session. Customer is locked and the
  cookie pair rechecked inside each service transaction. Logout revokes access.
- Both `bakery.web.enabled=true` and the existing
  `bakery.web.checkout.enabled=true` are required. Checkout remains **default off**.
  The entire `/api/account` subtree is gated before CSRF, identity or persistence,
  including unknown paths and writes without CSRF. Disabled routes return 404.
- Same exact-Origin allowlist, credentialed CORS and `Cache-Control: no-store,
  private` / `Pragma: no-cache` as the other website APIs. No redirects.
- PUT requires the real `X-XSRF-TOKEN` header matching `TRANCHE_WEB_CSRF`, as
  supplied by the existing session bootstrap. No new authentication scheme.
- Safe errors use `{valid:false,problems:[{code,message}]}`. Bad JSON/fields/page:
  400 `INVALID_REQUEST`; missing/invalid customer session: 401 `INVALID_SESSION`;
  stale profile version: 409 `PROFILE_CONFLICT`; missing/foreign/ineligible/bad-ID
  detail: uniform 404 `CHECKOUT_NOT_FOUND`; storage/lock failure: 503 `UNAVAILABLE`.
  Security-filter 403/disabled 404 may have an empty body. No private input or
  internal identifiers/SQL diagnostics are echoed in errors.

## GET /api/account/profile

Exact successful response (no extra fields):

`{version,name,address,deliveryPreference,loafPreference}`

- `version`: JSON safe integer, initially 0, persisted versions 1..9007199254740991.
- All other fields are strings, never null. Without a saved website profile,
  `name`/`address` may be suggested from the existing shared customer; absent values
  become `""`. Preferences are suggested independently from the customer's latest
  valid delivery/loaf choices on accepted non-cancelled RETAIL orders, excluding
  DRAFT and subscription orders. Shared Customer has no preference columns:
  WhatsApp keeps these on prior orders. Each query is scoped to the authenticated
  customer and allowlisted enum; absent/unsupported choices become `""`.
  A saved website profile takes precedence over all suggestions.
- GET never inserts a profile or records acceptance/consent to those suggestions.

## GET /api/account/location

Exact response: `{latitude:number|null,longitude:number|null}`. Both fields are
always present. Uses only the authenticated customer's shared saved pin, never a
customer/order identifier parameter. Any query parameter returns 400 after auth.
Incomplete or out-of-range stored pairs return both null; valid inclusive bounds
are latitude -90..90 and longitude -180..180. Zero is valid. No POST/PUT route.

The existing cookie-pair, independent rollout gates, exact Origin and private
no-store protections apply. No address, phone, ID or arbitrary URL is included.
No shared field/profile/order is changed; only existing auth activity can advance.
The frontend strictly validates numeric types/ranges and constructs an outbound
no-referrer Maps link only from the validated pair. No map embed, geolocation
permission, pin save or external request happens automatically. A nullable pair
or optional endpoint error never hides the profile; 401/403 still clears all PII.

## PUT /api/account/profile

Exact request and successful response fields:

`{version,name,address,deliveryPreference,loafPreference}`

All five request fields are required. Unknown or missing fields, nulls, arrays,
wrong JSON types and coercions are rejected. Specifically:

| Field | Input rule |
|---|---|
| version | JSON integer 0..9007199254740990; not a string, decimal or boolean |
| name | String, stripped, length 1..100 (Java UTF-16 units) |
| address | String, stripped, length 10..2000 (Java UTF-16 units) |
| deliveryPreference | Exactly `GATE`, `DOOR` or `IN_PERSON` |
| loafPreference | Exactly `SLICED` or `WHOLE` |

Name/address must each be one line: ISO control characters (including CR/LF,
tab, NUL and C1 controls) and Unicode line/paragraph separators are rejected
**before** trimming. Empty initial preferences are not valid saved preferences.
Successful saves return trimmed strings and `version + 1`.

V52 adds `web_customer_profiles`, keyed by customer FK with `ON DELETE CASCADE`.
No existing row means version 0. The customer lock serializes competing saves;
version 0 uses insert-if-absent and later versions use conditional update. Exactly
one concurrent request with a given current version wins; stale requests return
409 `PROFILE_CONFLICT` without overwriting anything. A final stored version of
9007199254740991 is readable but cannot be submitted as a new save version.

Saving this dedicated form is the explicit request to retain **website defaults
only**. It does not modify shared customer name/address, phone, coordinates,
pricing overrides/category or item prices, credit, delivery concessions,
subscription eligibility, marketing consent, existing orders or attempts.
The normal authenticated-session web-activity timestamp can still advance.
Checkout never implicitly creates or updates this saved profile. The frontend
must explicitly submit whichever fulfillment fields the customer accepts for
each checkout; profile defaults do not retroactively change past snapshots.

## GET /api/account/orders?page=0

Exact response:

`{orders:[{id,channel,orderNumber,createdAt,deliveryDate,orderStatus,status,totalAmount}],page,hasMore}`

- `page` is optional and defaults to 0. If present, it must be one canonical
  nonnegative decimal query string in 0..10000 (`0` or nonzero first digit).
  No signs, whitespace, leading zeroes, decimals, exponents, duplicate parameters,
  array aliases (`page[]`), arrays, extra query parameters or numeric coercion.
- Exactly ten entries per full page; no caller-supplied size. At most eleven rows
  are fetched (ten plus lookahead); `hasMore` is a boolean. Empty/beyond-end pages
  return an empty array and false. Offset pagination can shift across requests if
  a newer checkout is added; this is not a multi-page snapshot/cursor guarantee.
- The signed-in customer's **RETAIL WEB orders backed by an accepted
  `web_checkout_attempts` row** (`channel: "WEB"`), plus their numbered non-DRAFT
  RETAIL WhatsApp orders (`channel: "WHATSAPP"`). WhatsApp rows are summary-only:
  `id` and `status` are null, `deliveryDate`/`totalAmount` may be null, and there is
  no detail, recovery or payment link. Commercial, subscription-generated, DRAFT,
  unnumbered WhatsApp and unbacked WEB orders are excluded. WhatsApp `createdAt` is
  the order's IST creation time as an Instant. Clients must accept rows without
  `channel` (older backend) as WEB.
- Stable order: `createdAt DESC`, then WEB before WHATSAPP, then id DESC, including timestamp ties.
  V52 adds the matching customer/history index. `createdAt` is the accepted
  attempt timestamp as an ISO-8601 Instant; `deliveryDate` is the original quote
  date as `yyyy-MM-dd`. `orderNumber` is the human-facing order number (no numeric
  order-ID fallback). `id` is the opaque 32-character checkout attempt identifier.
- `totalAmount` is the accepted quote's original INR JSON number, never today's
  product pricing. `orderStatus` is the current persisted order state; `status`
  is the safe checkout/payment state described below. Status is not inferred from
  a return URL or from browser claims. Summaries do not contain addresses, names,
  phones, provider IDs or payment URLs.

## GET /api/account/orders/{opaque32}

Exact successful response:

`{id,orderNumber,createdAt,orderStatus,status,paymentUrl,expiresAt,fulfillment:{name,address,deliveryPreference,loafPreference},quote}`

- ID must be exactly 32 ASCII letters/digits/underscore/hyphen. Foreign, unknown,
  malformed and ineligible IDs return the same 404 envelope. Authentication is
  checked first. No numeric order/customer/provider identifiers are exposed.
- `createdAt` and `expiresAt` are ISO-8601 Instants. `paymentUrl` is a string only
  when PAYABLE and validated, otherwise **null** (field retained).
- Fulfillment is the original acceptance snapshot, not current shared customer
  fields or saved website profile. These four fields are the only fulfillment PII
  in the response and are visible only to the owning customer.
- `quote` is the original **StorefrontDtos.Quote** snapshot, exact shape:
  `{lines:[{itemId,name,quantity,unitPrice,lineTotal}],listSubtotal,discountAmount,discountLabel,batchDiscountAmount,batchDiscountLabel,deliveryCharge,giftLabel,creditApplied,totalAmount,itemCount,date,valid,problems:[{code,message}]}`.
  Optional labels serialize as null; amounts are JSON numbers in INR, date is
  `yyyy-MM-dd`. `valid` is historical acceptance validity, not a new availability
  or delivery reservation claim. Public catalog item IDs in quote lines are
  retained; internal customer/order/payment IDs are not. Product rename, reprice,
  retirement, cart edits and later profile changes do not alter this snapshot.
- Never serialize entities, idempotency keys, hashes, quote tokens, references,
  phone numbers, provider IDs or internal payment records.

## GET /api/account/checkout

Exact response: `{checkout: detail|null}`, where `detail` has the **identical**
shape above. Returns the latest eligible own attempt by the same timestamp/opaque
ID ordering, even when paid, cancelled or expired. Null means no eligible attempt.
Does not select an older payable attempt instead of the latest context.

Intended for memory-only frontend reload recovery: display exactly the recovered
snapshot, not a reconstructed cart/current profile. It contains **no original
idempotency key or quote token**, so it is not permission to repeat checkout POST.
Do not fabricate replacement keys or silently prepare/pay a new checkout from
the recovered detail. Ambiguous create outcomes require support reconciliation.

## Safe account payment state and side effects

Account reads share `WebCheckoutService`'s status calculation, ownership checks,
order/payment truth and strict hosted URL allowlist, but use an observation-only
entry point. This does **not** call create, dispatch, retry, cancel or any other
provider operation. GET never creates an order/payment/attempt, consumes a quote,
edits defaults, changes a basket, cancels an order or expires a provider link.
Only normal authenticated session/web-activity touches occur. No provider I/O is
performed while holding a transaction or at any later account-read stage.

| status | Account meaning |
|---|---|
| CREATING | Recent READY/CREATING attempt; no dispatch or retry by GET |
| UNKNOWN | Ambiguous dispatch, or READY/CREATING older than 120 seconds; support required |
| PAYABLE | Pending live order/payment, matching saved link binding, safe unexpired hosted URL |
| PAID | Gateway-captured payment with provider/payment/link binding and matching original quote/payment/order amount, plus CONFIRMED/IN_BAKING/OUT_FOR_DELIVERY/COMPLETED order |
| REVIEW_REQUIRED | Current payment/order review, or inconsistent captured binding/amount/provider; never represented as paid |
| CANCELLED | Current cancelled/cutoff-cancelled order, unless payment review takes precedence |
| EXPIRED | Known non-ambiguous unpaid attempt past deadline; no cancellation by this API |

READY/CREATING/UNKNOWN remain CREATING/UNKNOWN **even after their deadline**, unless
the current order/payment is cancelled, under review or verified paid. This keeps
uncertain dispatch visible to support instead of claiming the provider never
created it. A cancelled order with late money held for review retains
`orderStatus:CANCELLED` and `status:REVIEW_REQUIRED`. Mismatched original quote,
order and payment totals also suppress payment URLs/paid claims. No captured
label alone is proof of payment.

PAYABLE URLs require matching saved attempt/payment link identity and HTTPS on
exact host `rzp.io` or `razorpay.com`, no credentials, fragment, control characters
or unusual port. Only PAYABLE may have a non-null URL. Existing direct
`GET /api/checkout/{id}` expiry/revocation behavior is retained; account reads
deliberately omit that provider-side action.

History/detail/recovery use short bounded transactions, acquiring the global
capacity lock **before** the locked customer/session and any attempt work, never
inverting checkout/settlement lock order. Profile reads/saves lock only the
customer/session/profile and never request capacity later.

## Deliberate limitation: paid basket retention

No automatic paid-cart cleanup was introduced. The existing accepted cart remains
edit-versioned; account status/recovery cannot delete it or newer edits. A future
server-side cleanup must condition on the captured attempt's **exact** consumed
cart version, advance rather than reset the cart version, be idempotent under
webhook replay, and establish safe customer/cart locking with the existing
capacity/attempt settlement topology. Existing settlement tests currently retain
the consumed basket for an explicit next purchase; that behavior is unchanged.
Do not add unconditional frontend clearing on recovery/payment polling.

## Parent-owned validation still needed

No terminal, Maven, tests or builds were run for this change. Editor diagnostics
are not execution evidence. New `WebAccountHttpTest` uses local real PostgreSQL,
MockMvc, real cookie-pair auth and CSRF headers, and mocked provider/notifications.
It covers profile defaults/CAS/races/validation/max-safe version/cascade;
ownership/admin-cookie spoof/logout/idle/expiry/Origin/CSRF; bounded pagination,
stable ties/privacy/scope; snapshot immutability/catalog rename; safe URL and
amount/status recovery; uncertain expiry/no redispatch; signed-settlement paid
recovery; and checkout independence from saved defaults. Existing disabled suites
cover account routes before CSRF and authentication when either gate is off.

Parent should run Flyway migration + the account/disabled/cart/checkout/security,
webhook/cutoff/capacity regression suites, then the full Java-17 Maven test suite.
Inspect SQL/JPA fixture compatibility and all compiler warnings. No live provider,
production, browser UX/E2E or load validation is claimed.