# Shopping snapshot execution — 2026-10-27

## Scope and completion

Backend-only atomic change. No scenario root, assessment.md, task.md or forwarded
task skills were supplied; generic Java skill search found none and Execution
extensions returned none. Before source edits, inspected actual pricing, delivery,
security and cart/checkout code and enriched the existing plan.md execution reference.
No task folders/lifecycle state were created. Objective was not already implemented.

- Added strict read-only POST /api/shopping and explicit DTO projection; optional
  verified identity uses the same filter as quote, preserving CSRF/Origin/ApiGuard
  and no-store behavior. Public preview works while checkout is disabled.
- Uses shared pricing for quantity-one current winning public/item/category/global
  rates, before batch, delivery or credit. Date-scoped batch preview is explicitly
  for quantity one; no authoritative basket subtotal/total is falsely exported.
- Seven base dates retain fully booked dates, clamp remaining allowance 0..5 and
  list eligible active IDs with existing cutoff/bagel/weekend/F&F rules. Date input
  is restricted to that finite horizon. No raw demand/capacity/customer data exposed.
- WEB cart save and prepare/new checkout enforce five total units after duplicate
  merge. Legacy oversized stored cart reads remain intact; save requires reducing
  to <=5. Existing accepted oversized idempotency/status recovery is preserved.
- No schema, WA flow, subscription, shared-pricing semantics, frontend, parked
  files, bundles, live OTP/payment/order or deployment changes.

## Files changed

All paths relative to this backend repository:

- docs/storefront/plan.md — pre-edit verified execution scope/decomposition.
- docs/storefront/shopping-contract.md — exact request/DTO, security, estimates,
  date privacy, frontend behavior and legacy cart policy.
- docs/storefront/shopping-progress-details.md — this validation evidence.
- src/main/java/com/tranche/bakery/web/auth/WebApiSecurity.java
- src/main/java/com/tranche/bakery/web/shop/StorefrontCatalogService.java
- src/main/java/com/tranche/bakery/web/shop/StorefrontController.java
- src/main/java/com/tranche/bakery/web/shop/StorefrontDtos.java
- src/main/java/com/tranche/bakery/web/shop/WebRetailPolicy.java
- src/main/java/com/tranche/bakery/web/shop/WebCartService.java
- src/main/java/com/tranche/bakery/web/shop/WebCheckoutService.java
- src/test/java/com/tranche/bakery/web/shop/StorefrontQuoteTest.java
- src/test/java/com/tranche/bakery/web/auth/WebAuthDisabledTest.java
- src/test/java/com/tranche/bakery/web/auth/WebCheckoutDisabledTest.java
- src/test/java/com/tranche/bakery/web/auth/WebCartHttpTest.java
- src/test/java/com/tranche/bakery/web/auth/WebCheckoutHttpTest.java

## Validation evidence

- Full prescribed JDK 17.0.20.1/system Maven `-B clean test`, no timeout, sole build
  process: BUILD SUCCESS, exit 0, **439 tests / 0 failures / 0 errors / 0 skipped**
  (baseline 398). All providers mocked; integration uses local PostgreSQL/loopback.
  Full run completed at 20:04:54 IST, 4m23s. Surefire XML retained under target.
- Final confirmation after removing newly added nullability suppressions: repeated
  full clean Maven at 20:11:11 IST, 3m58s, again 439/0/0/0 and exit 0. No new
  suppression remains; all changed Java files have clean editor diagnostics.
- Tests cover strict body, finite date horizon, exact DTO/privacy fields,
  public versus item/category/global/expired override rates and public winners,
  batch/override suppression, mixed-basket/rounding non-additivity, date eligibility
  and capacity boundaries including overfull dates, security/cache/disabled states,
  session spoofing/misbinding/expiry/DB failure, cart five/six/duplicate/distinct IDs,
  oversized legacy reads/reductions, prepare/new acceptance, and accepted historical
  six-item replay/status for PAYABLE/CREATING/UNKNOWN/PAID. Existing competing WEB
  and WEB-vs-WA capacity races were adapted to allowed WEB quantities and still pass.
- First focused run: 147 tests, two new test assertions incorrectly expected a
  five-item quote to fit the cart test profile's daily capacity of THREE. Corrected
  tests to assert advisory FULLY_BOOKED while the five-item save succeeds. No
  production behavior was changed to satisfy those tests.
- Editor diagnostics: zero errors/warnings in changed Java files. Full compile has
  existing @MockBean deprecation warnings in untouched FlowScenarioBase and
  PaymentLinkRevocationTest; existing environment/framework warnings (PostgreSQL 18
  versus Flyway support, dialect/open-in-view, follow-on locking, JVM CDS) remain.
  No new suppression or dependency upgrade introduced.
- git diff --check clean. Temporary root test logs removed; no bundle generated.

## Review / constraints

Focused read-only review found no correctness/security blocker; noted per-product
shared-pricing evaluation repeats offer/customer-order-count/batch reads. Retained
the simple existing authoritative service rather than introducing a second pricing
engine or changing quote semantics. Snapshot is fetched on bootstrap/auth/date
changes, not per quantity click. This is a future request-scoped query batching
optimization, not an exact-basket guarantee or a per-unit rule export.

Frontend implementation/visual verification is intentionally not performed. It must
follow shopping-contract.md: estimated product subtotal only, no derived payable
total or multiplied batch discount, no rendered capacity counts, discard snapshots
on identity change/failure, and authoritative prepare/final checkout confirmation.