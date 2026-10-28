# Website account API implementation — 2026-10-26

## Pre-edit source research and scope

- Confirmed `WebCartService.requireCustomer` joins the caller transaction and
  `CustomerSessionService` locks the customer then revalidates both opaque cookies.
- `WebApiSecurity.ApiGuard` currently gates cart/checkout before CSRF; extend that
  same guard to the entire account path subtree (including the bare path).
- V51 is the latest migration. V52 will own website-only profile CAS storage;
  checkout stores immutable fulfillment/quote JSON in `web_checkout_attempts`.
- `WebCheckoutService.status` owns safe payment state/URL computation, but its
  expired path can cancel provider links after commit. Account reads must share
  the status computation without that provider side effect, preserving uncertain
  expired dispatches for support. Acquire capacity before customer locks on all
  account history/status transactions; profile operations need no capacity lock.
- Bound history to eleven rows (ten plus lookahead), scoped to matching attempt
  and order customer, WEB/RETAIL, no subscription and no DRAFT. Read stored quote,
  never current catalog or saved profile. Add a matching history index in V52.
- Tests will use real PostgreSQL + MockMvc, established cookie-pair session fixture
  and real `X-XSRF-TOKEN`, not mocked identity or CSRF test bypasses.
- Optional paid-cart cleanup is deferred: the settlement path locks capacity then
  attempt/payment, while cart edits serialize customer then basket. Adding cleanup
  here would also change existing verified-capture/rebuy tests that deliberately
  retain cart version 1. No settlement/provider behavior will be changed in this
  account-only pass; newer cart edits therefore cannot be deleted by this feature.

## Decomposition / validation boundary

This is a direct existing-Java feature request, not a modernization scenario. No
scenario skill root, task.md, or workflow directory was supplied; no applicable
execution extension/Java skill was returned. Scope is the single account API
contract and its security/storage/status integration. No task lifecycle changes.
The parent explicitly owns later build/test execution: no terminal, build, test
execution, production access, provider access, parked work or frontend changes.

## Implementation / static self-check

- Added `WebAccountController` / `WebAccountService` and V52 profiles/history index.
  Profile reads/saves are explicit projections with customer-serialized CAS; no
  shared customer mutations (except existing session activity tracking).
- Extended `WebApiSecurity`'s checkout-disabled guard to account root/subtree and
  `StorefrontProblemAdvice` to the new controller. Added disabled-route tests.
- Shared checkout status calculation through an observation-only account entry
  point: no provider I/O, expiry cancellation or attempt aging writes on account
  GET. Preserves expired uncertain context and late-payment review. Account paid
  claims require matching original quote/order/payment totals and saved bindings.
- Added `WebAccountHttpTest` with real PostgreSQL/MockMvc fixtures, actual cookie
  pair and CSRF header; mocked external boundaries only. Includes concurrent
  first/subsequent saves, privacy/scope/pagination/ties, historical quote/name
  immutability and verified-settlement recovery, no redispatch/provider I/O.
- Static focused review flagged cancellation hiding late-payment review; account
  reads now prioritize review without changing the direct checkout route's order.
- Initial editor diagnostics reported no errors in all eight Java files checked.
  This is not compilation/test evidence. No terminal/build/test execution occurred.
- The precise API, all nullable fields, status differences and limitations are in
  `account-contract.md`. Optional paid-cart cleanup remains deferred as above.

### Parent validation handoff

Apply V52 on the local PostgreSQL fixture, compile under Java 17, run
`WebAccountHttpTest`, `WebCheckoutDisabledTest`, `WebAuthDisabledTest`, then existing
cart/checkout/webhook/cutoff/capacity and full regressions. Check warnings as well
as failures. No passing test/build claim is made by this implementation worker.
No task.md/progress workflow paths were supplied, so research and append-only
execution evidence are recorded here instead of inventing workflow task folders.

## Account/profile escalation correction — 2026-10-28

- Execution extension lookup: none apply. Preserved pre-existing AuthCrypto/phone
  work and all website copy/phone changes. No lifecycle tools, task folders,
  parked source, production account/provider/OTP access, push or bundles.
- Baseline account tests passed before edits. Added real random-port loopback HTTP
  on the local test database to create cart/quote/checkout, then exercise actual
  settlement binding and read PAYABLE/PAID history and detail. Providers and
  notification boundaries stay mocked; this is not a real payment/webhook claim.
- Passed serialized backend projections through the actual sibling Node validators.
  Observed createdAt `2026-10-27T18:46:23.918016Z`, expiresAt
  `2026-11-05T17:30:00Z`, string date and numeric total; validators accept them.
  No local Jackson timestamp defect found; no serializer config was changed.
- Injected a null quote only into a synthetic test attempt: history returned 500,
  same-cookie profile stayed 200. Before fix, frontend wire regression failed
  because profile was null. Root cause proven: refresh Promise.all couples profile
  to unrelated history. Hosted initiating fault remains unknown, not inferred to
  be corrupt history, a cookie problem, or a timestamp defect.
- Backend changes: WebAccountService/Controller suggest name/address from shared
  Customer and safe preferences from own accepted non-subscription retail orders
  only when no dedicated profile exists. Separate GET location is own-cookie-only,
  no query parameters, exact numeric/null pair with inclusive geographic bounds.
  No write endpoint, shared-default mutation, entity serialization or migration.
  Strict five-field profile GET/PUT, CAS and checkout prefill remain compatible.
- Backend tests changed: WebAccountHttpTest, WebAccountServiceTest,
  WebAuthDisabledTest, WebCheckoutDisabledTest. Cover own/foreign auth, Origin,
  read-only methods, disabled gates, shared-data immutability (excluding the normal
  auth web-activity timestamp), prior preference scope/profile priority, nullable/
  incomplete/out-of-range/zero/boundary pins and actual paid wire responses.
- Frontend changes: account page/controller/model, exact API allowlist and Vercel
  location rewrite; account/API/routing/wire tests plus one stale checkout copy
  assertion. Profile is independent, history explicit-click, optional pin errors
  cannot hide profile, refresh bursts coalesce and privacy generations/DOM wiping
  remain. Top refresh/instruction removed, Sign in shortened, reload/discard hidden
  normally and shown only for failure/conflict recovery. Detailed frontend files
  and evidence also appended to sibling storefront/progress-details.md.
- Final validation: clean Java-17 system Maven **447 tests / 49 suites**, zero
  failures/errors/skips. Full frontend **340/340**, no skips/cancellations;
  **26/26** source/test script syntax checks; both git diff --check and scoped
  editor diagnostics pass. Initial new test assertions were corrected to use the
  prepared quote amount (acceptance has no quote) and exclude documented normal
  auth activity, not by weakening production settlement/data protections.
- Limits/decision: hosted initiating fault and native-browser/hosted cookie,
  Vercel routing and UX are not validated here. Backend-first deployment remains
  required; older clients ignore the separate endpoint, new frontend tolerates
  it being unavailable. No account merge/data migration decision is needed.