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