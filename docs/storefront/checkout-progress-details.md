# T006 backend execution — 2026-10-26

## Current verdict — final local backend integration

**Backend pilot implementation validated, still default-off.** Final system Maven
Java-17 `-B -q clean test`: **348 tests in 45 suites, 0 failures, 0 errors,
0 skipped**. Real local PostgreSQL 18.3; provider/WhatsApp mocks only. No JSON DTO
contract change, frontend, parked implementation, bundles, push, deploy or live
provider operation. This supersedes the historical partial verdict below. Full
T006/UI/rollout completion remains parent-owned. Final evidence and review are
appended at the end of this document.

## Historical cart-only verdict (superseded)

**Partial only: durable carts implemented; transactional retail checkout NOT
implemented.** Do not mark T006 complete or enable a payment button. All changes
are in the backend repository. No frontend, rollout guide, bundles, push,
deployment, parked implementation, provider call or real OTP was used.

## Research / decomposition / pre-edit evidence

Read storefront spec, constitution, plan and architecture, then researched current
web auth/session/CSRF, quote and CartPricingService, order/channel migrations,
Razorpay/payment/webhooks, credit confirmation and cutoff/capacity paths. Full
T006 spans independent units with hard validation boundaries. A nested
decomposition assessment returned **non_atomic** and recommended durable carts as
the smallest coherent safe core. Parent supplied no workflow/task.md,
progress-details path, assessment artifact or scenario skill root; none was
invented and no lifecycle tools were invoked. Skill recovery found no matching
upgrade skill; Execution extensions returned none. Runtime-validation skill was
read; explicit user local-PostgreSQL/no-Docker constraints take precedence.

The contract was the first file written, containing research, exact DTOs, scope,
limitations and test strategy before any source edit. It is the execution
reference in lieu of a nonexistent task.md. Tests were then written and executed
before source: nine expected failing tests (missing routes/disabled CSRF guard).

## Implemented

- Separate default-off checkout flag independent of API and OTP gates; early 404
  for new routes while disabled, leaving catalogue/preview available.
- One durable owned cart per customer, no DRAFT/conversation coupling. Read does
  not create a row. Strict quote parser and additional 60-day date bound;
  normalized duplicate item lines, optimistic edit version compare-and-swap on
  first insert and replacement. Storage bounded to 40 lines, 1..99 quantities.
- Cookie-only customer identity, transactional customer/session lock through
  save; admin/basic/claimed header cannot authenticate. Unsafe requests use
  existing CSRF, exact origin policy, no-store. No personal IDs or phone in DTO.
- Fresh server pricing includes F&F and credit without consuming or suppressing
  either. Aged/retired baskets retain edits but return quote:null and safe
  problems; price changes re-quote without pretending cart version binds price.
- Checkout routes explicitly fail closed: POST 503, owned authenticated lookup
  404 (there are no attempts). Never create orders/payments, perform provider
  calls, reserve capacity/credit, offer QR fallback or mark redirected users paid.
- Catching a stale validation error originally marked the enclosing transaction
  rollback-only. Fixed production quote service with noRollbackFor for this pure
  validation exception, not by weakening the regression test. Removed existing
  touched-service null-safety diagnostics without suppression.

## Exact changed files (repository-relative)

- docs/storefront/checkout-contract.md
- docs/storefront/checkout-progress-details.md
- src/main/java/com/tranche/bakery/web/auth/WebApiSecurity.java
- src/main/java/com/tranche/bakery/web/shop/WebCartController.java
- src/main/java/com/tranche/bakery/web/shop/WebCartService.java
- src/main/java/com/tranche/bakery/web/shop/StorefrontCatalogService.java
- src/main/java/com/tranche/bakery/web/shop/StorefrontProblemAdvice.java
- src/main/resources/application.properties
- src/main/resources/db/migration/V48__web_durable_carts.sql
- src/test/java/com/tranche/bakery/web/auth/WebCartHttpTest.java
- src/test/java/com/tranche/bakery/web/auth/WebCheckoutDisabledTest.java

## Test evidence

- WebCartHttpTest: 8 real-loopback Tomcat/local PostgreSQL tests covering all four
  new routes; ownership, new-cookie-session persistence, duplicate merging,
  competing initial/replacement saves, strict integer/ID/date/field bounds,
  non-mutation on invalid inputs/conflicts, catalogue repricing/retirement/aged
  basket, advisory capacity, F&F/credit/zero amounts, cookie expiry, CSRF, claimed
  customer/admin/basic rejection, cross-origin denial and no provider interactions.
- WebCheckoutDisabledTest: 1 independent default-off regression; API still on,
  OTP off, new routes rejected before identity/CSRF/service mutation.
- Initial implementation focused run: 8 passing, 1 stale-cart rollback failure;
  production fix applied. Complete requested Maven clean test subsequently:
  **259 tests, 0 failures, 0 errors, 0 skips; BUILD SUCCESS** (1m46s).
- Read-only focused code review: no concrete defects; corrected its one contract
  discrepancy about transaction propagation. Warning that the earlier red report
  was stale is superseded by completed full green run.
- Runtime emits existing environment/config warnings (PostgreSQL 18 newer than
  bundled Flyway certified 17, explicit dialect, open-in-view and test fixtures).
  No assertion failures; no claim that the legacy repository is warning-free.
- Final null-safety-only refactor requires repeat validation, recorded below.

## Unsupported branches / next parent decision

Continue T006 in dependency-ordered backend units; do not roll out checkout yet.
Need shared database concurrency protection across website AND existing WhatsApp/
other capacity writers; immutable accepted quote/cart binding; complete
name/address/fulfillment/loaf snapshot and independent WEB order; durable
customer+key unique attempts, single active attempt and stale-attempt policy;
outside-transaction provider dispatch with durable UNKNOWN timeout reconciliation;
stored allowlisted HTTPS Razorpay link and signed amount/link-confirmation;
late payment/cutoff/cancellation integration; credit-bearing support gate or
reliable reservations spanning legacy payments; zero-payable manual review.

No successful checkout/idempotency/crash/provider-timeout integration tests exist
because those paths are intentionally not implemented. Existing WhatsApp/payment
tests passing is regression evidence, NOT evidence of new retail checkout. No
history/subscriptions/new order preferences were implemented. Cart storage is one
bounded row/customer with customer-delete cascade; no automatic age-deletion job,
and stale reads never reserve capacity or consume credit.

## Final exact-source validation

- Repeated the requested `clean test` after the final null-safety refactor:
  **259 tests, 0 failures, 0 errors, 0 skipped**. WebCartHttpTest 8/8 and
  WebCheckoutDisabledTest 1/1, all through the normal Maven suite.
- Editor diagnostics: zero errors/warnings in all seven touched Java files.
  Existing framework/runtime environment warnings above remain out of scope.
- `git diff --check` passes. Eleven changed/new files, exclusively backend code,
  tests, migration, properties, this evidence and the first-written contract.
- No task.md supplied/created; contract enriched before source edits. This file
  records progress without changing parent-owned plan or claiming T006 complete.

## Bounded WEB webhook boundary — 2026-10-26 (validation pending parent)

- Read the replacement contract and this progress first. No scenario/task/assessment/
  skill root was forwarded; no lifecycle calls or invented workflow files. Skill
  recovery and Execution extensions returned none. Parent's prior non-atomic
  decomposition leaves this bounded webhook/Admin boundary atomic.
- Added WebWebhookSafetyTest BEFORE source changes: 14 local-database/signed-body
  regressions for CREATING/UNKNOWN early binding, retries, ordinary-route bypass,
  dual signed reference agreement, strict positive integer paise/INR/provider IDs,
  saved-link preservation, amount mismatch, READY exclusion, expiry/cutoff,
  conflicting payments, screenshot exclusion and no WEB credit consumption.
- Exact new AdminService overload:
  `confirmGatewayPayment(Long orderId, String gatewayPaymentId, String gatewayLinkId,
  BigDecimal paidAmount, String webAttemptId, String providerReference, String currency)`.
  Controller passes reference_id ONLY when it exactly equals signed notes.webReference;
  missing WEB notes cannot fall back to the trusted four-argument cutoff overload.
  The old overload requires BOTH saved attempt and saved Payment link IDs to match
  for WEB; it cannot bind an early link. Both overloads revalidate the WEB attempt,
  amount against Payment AND order total, provider, IDs, deadline and active status.
- WEB early binding requires matching signed attempt/reference/INR, CREATING or
  UNKNOWN, live deadline, and both previously empty link IDs. Binds attempt link
  and Payment link in one Spring transaction, without changing attempt state/URL.
  Parent dispatcher must preserve an already-bound identical link and captured
  Payment when its provider call returns; conflicting persistence must fail closed.
- Lock ordering: CapacityLock.acquire() FIRST, before any order/payment/attempt row
  access; attempt SELECT FOR UPDATE then fresh clock_timestamp() expiry check after
  lock waits (not PostgreSQL transaction-start now()). Admin approval, gateway,
  review/date/status/cancellation mutations share the same capacity lock. No edits
  to capacity/order services, checkout files, cutoff, migrations or frontend.
- Failed WEB evidence marks Payment REVIEW_REQUIRED (including conflicting later
  money) and only moves awaiting orders to PAYMENT_REVIEW_REQUIRED; cancelled or
  progressed orders are not revived. Original provider IDs remain unchanged.
  A conflicting second charge after capture deliberately requires manual review;
  a later retry does not erase that review decision. Ordinary approval cannot
  substitute SCREENSHOT_VERIFIED for WEB gateway verification. WEB consumes no credit.
- Read-only review caught transaction-start expiry risk; fixed with post-lock DB
  wall-clock check. Followed explicit mismatch-review contract rather than reviewer
  suggestion to retain GATEWAY_CAPTURED on conflicting later evidence.
- No Maven/test executions: explicitly deferred to parent during concurrent source
  edits. No live provider/network operations. git diff --check passed; controller
  editor diagnostics clean; final Admin/test diagnostics checked separately below.
  CapacityLock is now present. V49 attempt migration remains parent-owned and must
  exist before the 14 tests execute. No claim of runtime/build success for this unit.
- Final static check: zero editor errors/warnings in AdminService,
  RazorpayWebhookController and WebWebhookSafetyTest; git diff --check clean;
  counted 14 new tests. Parent must run focused and full Maven validation.

## Shared capacity boundary — 2026-10-26 (bounded continuation; tests deferred to parent)

Read checkout-contract.md and this append-only history before editing. The contract
already recorded scope/research before source. No scenario/task.md/assessment/skill
root was forwarded; none invented. Recovered skills: no matches; Execution
extensions: none. Prior TaskBreaker non_atomic verdict applies to the full checkout;
this assigned shared-capacity boundary is coherent/atomic. Tests were written
before source and extended after review; no lifecycle/decomposition tools invoked.

### Exact files changed by this capacity worker

- src/main/java/com/tranche/bakery/order/CapacityLock.java (new)
- src/main/java/com/tranche/bakery/order/OrderService.java
- src/main/java/com/tranche/bakery/order/OrderItemRepository.java
- src/main/java/com/tranche/bakery/order/CommercialOrderService.java
- src/main/java/com/tranche/bakery/order/CutoffJob.java
- src/main/java/com/tranche/bakery/subscription/SubscriptionService.java
- src/main/java/com/tranche/bakery/flow/actions/ReviveLatePaymentAction.java
- src/test/java/com/tranche/bakery/CapacityBoundaryTest.java (new)
- docs/storefront/checkout-progress-details.md (this appended section)

No AdminService, RazorpayWebhookController, payment, web/shop or migration file was
edited by this worker. Those concurrent changes remain independently owned.

### Contract and lock ordering — parent integration requirement

CapacityLock.acquire() is a public Spring service operation with MANDATORY
propagation and an explicit active/writable transaction guard. It uses the caller's
transaction-bound JDBC connection and PostgreSQL transaction advisory key
23734322354538565. No autocommit, session-scoped or REQUIRES_NEW lock. Normal lock
waits are bounded to five seconds via LOCAL lock_timeout, restoring the caller's
previous value after acquisition; PostgreSQL releases at commit/rollback.

Required normal ordering: capacity -> customer/session -> order/payment/subscription
rows. New web checkout MUST acquire capacity before session/customer resolution,
not merely immediately before the order INSERT. Likewise admin/webhook entry points
must acquire it before order/payment writes or credit consumption. Commercial
insertion locks before customer creation/update; subscription activation locks
before its atomic status flip and credit consumption; cutoff locks before gateway
settlement/admin confirmation. Read-only/pricing/session-only paths need no capacity
lock. No subscription date-shift method exists in SubscriptionService; admin
delivery-date/status operations are the other worker's responsibility.

Draft edits/cancellation use conditional per-order FOR UPDATE NOWAIT, without the
global mutex. Reserved edits acquire capacity before refreshing/locking their row.
If a single WA flow edits a draft and then enters reservation in the same
transaction, a synchronization marker makes subsequent capacity acquisition
pg_try_advisory_xact_lock only: contention fails immediately and the ENTIRE flow
must retry, never wait while holding the draft row. This also handles a transaction
that already owns capacity (reentrant try succeeds). Draft-only merges/no-merge
resolveCart do not acquire global capacity. Draft row contention is retryable.

OrderService.confirm now reads/refreshed-locks current persisted order and line
quantities/menu under capacity, permits only DRAFT/PENDING_CONFIRMATION, rejects
empty/nonpositive/retired lines, applies current DeliveryRules item/day/lead-time
and F&F exceptions, and compares total actual quantity + other reservations against
the daily cap. Its own persisted reservation is excluded. Pending add/merge also
revalidate and roll back on rejection; pending amount edits revoke old links.
Late screenshot revival now delegates to the same bounded reservation boundary.
Runtime repricing reloads state so a stale pending/draft cannot revive a cancelled
order; the existing direct-constructor pricing-only unit fixture remains supported.

WA merge/add/remove/reprice/confirm and customer cancellation reject WEB; awaiting
payment/payable/late-revival WA lookup excludes WEB. Cutoff intentionally still
cancels WEB pending orders under the shared lock (per checkout contract), but never
resets their customer's WA conversation. Commercial and prepaid-subscription manual
capacity overrides are preserved, not broadly disabled; they serialize, and future
retail acceptance sees their booked quantities. INVOICED remains unreserved until
paid, matching existing policy. Scheduled external calls still hold the mutex for
their transaction duration; only the acquiring caller's lock wait is bounded.

### Exceptions and remaining parent-owned integration

- Missing/read-only transaction: IllegalTransactionStateException (MANDATORY also
  rejects a missing transaction before method execution).
- Capacity: IllegalStateException("FULLY_BOOKED"); date/item-day/lead-time:
  "INVALID_DELIVERY_DATE"; invalid state/empty/retired/nonpositive: "INVALID_ORDER";
  WEB mutation: "WEB_ORDER_IMMUTABLE". These are rollback failures, not successes.
- Advisory/draft-row contention: Spring pessimistic-lock/data-access failure
  (CannotAcquireLockException for fail-fast escalation); retry only the whole
  rolled-back transaction. No provider dispatch must occur after such a failure.
- Parent must map checkout FULLY_BOOKED to 409 and contention to a safe retryable
  response; do not catch rollback failures and continue inside the same transaction.
  Focused review identified the legacy FlowEngine catch-all as an integration risk:
  it swallows action exceptions and continues to payment. Parent must arrange an
  OUTER rollback-safe retry/date re-prompt for newly possible confirm/merge/revival
  rejection; this worker did not broaden edits into FlowEngine/error UI.
- Parent must also filter legacy payment-screenshot/order-selection actions for
  WEB: those payment-owned routes were deliberately not edited here. The shared
  lock alone does not enforce immutable payment snapshots in unmodified callers.

### Evidence and validation handoff

Nine local-PostgreSQL tests in CapacityBoundaryTest cover MANDATORY, bounded advisory
wait and draft edits without global capacity, fresh cap/self exclusion, actual
persisted quantities, weekday/weekend/bagel lead rules, pending merge/growth
rollback, WEB immutability, revival/stale repricing, competing last-slot confirms,
and legacy confirm observing a committed shared-lock reservation. No Docker,
provider or OTP calls. Existing SubscriptionServiceTest/CommercialOrderServiceTest
remain unchanged and must run for policy regression evidence.

Final editor diagnostics: 0 errors/0 warnings in all eight changed Java files.
git diff --check passed. Focused read-only review prompted bounded waits, fresh
line/entity reads, per-draft row coordination and fail-fast inversion avoidance.
No Maven build/tests were run, explicitly to avoid racing the parent's/other
worker's shared target output. Consequently there is NO runtime-green claim.

Parent command from TrancheWA-App with JAVA_HOME=C:\Program Files\Java\jdk-17.0.20.1:
& 'C:\Program Files (x86)\Maven\mvn\bin\mvn.cmd' '-Dtest=CapacityBoundaryTest,SubscriptionServiceTest,CommercialOrderServiceTest' test
Then run the existing WA-flow/cutoff/payment regressions and the complete suite
after all worker files are integrated; do not run clean while another build runs.

## Capacity escalation fixes — 2026-10-26 (Maven explicitly deferred)

Read the current replacement checkout contract and initial checkout-focused.log.
Execution extensions returned none; no scenario root or lifecycle ownership was
supplied, and no lifecycle calls were made. This pass changes capacity/legacy WA
helpers only, not AdminService, the web checkout implementation, provider services,
webhook controller, migrations or frontend.

### Root causes and fixes

- PostgreSQL 55P03 from advisory lock_timeout was UncategorizedSQLException with
  this JDBC translator. CapacityLock now maps that SQLSTATE, retaining its cause,
  to CannotAcquireLockException; no SQL runs after the aborted statement. The same
  mapping covers OrderService's draft FOR UPDATE NOWAIT. Existing assertions still
  require Spring pessimistic locking failures; they were not weakened.
- CapacityBoundaryTest incorrectly created multiple DRAFTs for one customer.
  Invalid-date fixtures now cancel the prior rejected draft; the competing-confirm
  fixture uses a distinct second customer. The production uniqueness constraint
  remains intact.
- FlowEngine's action catch-all swallowed new reservation rejection and advanced
  into payment while Spring had marked the encompassing transaction rollback-only.
  Reservation validation now throws CapacityReservationException (an
  IllegalStateException subtype with unchanged codes). FlowEngine owns the whole
  flow via TransactionTemplate, rethrows reservation/lock failures from actions,
  and only replies/re-prompts in a fresh transaction AFTER rollback. The old
  ConversationService outer transaction was removed so it cannot defer rollback
  past recovery. Date/capacity rejection preserves the draft; late-payment revival
  retains its own LATE_PAYMENT_DATE route and screenshot. No automatic replay or
  provider call is attempted after rejection.
- WEB exclusion now covers screenshot lookup, selection, direct application,
  auto-confirm and late revival; stale/forged WA confirm/payment contexts also
  refuse WEB or a different customer's order. hasPendingPayment is WA-only, as
  required by its late-payment routing caller. Screenshot helpers remain separate
  from the concurrently owned web checkout/payment boundary.

### Lock ordering inspection

Confirmed WebhookHandler calls CustomerService.findOrCreate through its own
transactional proxy and that transaction completes before ConversationService /
FlowEngine. Normal confirm is a separate inbound message from saved address,
location, delivery preference and loaf preference; it takes capacity before
refreshing/locking the order, with no retained customer write lock in those paths.
Conversation-to-customer has no cascade writes. No concrete customer-before-capacity
inversion was found in this call chain, so no speculative global lock was added to
every menu/profile message. Existing same-transaction draft-edit escalation remains
try-lock-only and rolls back rather than waiting while holding its row lock.

### Files changed in this escalation pass

- src/main/java/com/tranche/bakery/order/CapacityLock.java
- src/main/java/com/tranche/bakery/order/CapacityReservationException.java (new)
- src/main/java/com/tranche/bakery/order/OrderService.java
- src/main/java/com/tranche/bakery/flow/FlowEngine.java
- src/main/java/com/tranche/bakery/conversation/ConversationService.java
- src/main/java/com/tranche/bakery/flow/actions/ConfirmOrderAction.java
- src/main/java/com/tranche/bakery/flow/actions/ReviveLatePaymentAction.java
- src/main/java/com/tranche/bakery/flow/actions/SavePaymentScreenshotAction.java
- src/main/java/com/tranche/bakery/flow/actions/ApplyPaymentScreenshotAction.java
- src/main/java/com/tranche/bakery/flow/actions/ShowPaymentOrderSelectAction.java
- src/main/java/com/tranche/bakery/flow/actions/AutoConfirmOrderAction.java
- src/main/java/com/tranche/bakery/flow/actions/SendPaymentQrAction.java (guard only)
- src/test/java/com/tranche/bakery/CapacityBoundaryTest.java
- docs/storefront/checkout-progress-details.md (this appendix)

### Validation and recommended parent runs

No Maven/build/test process was started: parent explicitly owns the concurrent
focused run and shared target/database. Editor diagnostics and git diff --check
are the only validation here; runtime green is NOT claimed. CapacityBoundaryTest
now has 18 tests (nine added), including actual ConversationService inbound failure,
empty-draft confirmation, advisory timeout recovery, draft escalation rollback,
NOWAIT contention, late-revival date rollback + screenshot retention, WEB-only and
mixed-channel screenshot routing, and forged WEB selection. Its properties force
UPI_QR and empty gateway credentials, preventing accidental real gateway dispatch.

After the current parent run ends: CapacityBoundaryTest, then OrderFlowTest,
OrderChoiceFlowTest, CreditFlowTest, ItemCapFlowTest, SubscriptionServiceTest,
CommercialOrderServiceTest, payment.PaymentLinkRevocationTest and the existing
cutoff/late-payment regressions; finally the complete clean test suite, serially.
If old fixtures confirm empty/undated drafts, preserve the new production
validation and repair only genuinely obsolete setup after inspecting failures.
Existing web-focused failures in the initial log remain parent-owned; none were
silently reclassified as capacity regressions or fixed by editing checkout files.

Final static verification: all 13 Java files changed by this escalation report zero
editor diagnostics; git diff --check passes (only existing Git LF-to-CRLF notices).
Confirmed 18 capacity tests by source count. They have NOT been executed here.

## Final backend continuation / security review — 2026-10-26

### Scope and existing handoff verified

Read this history and checkout-contract.md, git status/diff and current checkout,
cart/session/security, capacity/order/flow, Admin/webhook, provider, cutoff,
subscription/commercial code and tests before editing. Execution extensions:
none apply. No task lifecycle calls, task folders or workflow artifacts created.
All retained changes are inside TrancheWA-App. No parked source was consulted.

The cart assertions already expected 400 INVALID_REQUEST, not the old 503 stub;
test Hikari pools already had maximum=3/minimumIdle=0/idleTimeout=10000; safeUrl
already rejected null/hostless URI hosts before Set.of.contains; Admin gateway
settlement already committed and cleaned up its transaction before notifications.
Cutoff already fetched/revoked outside its capacity transaction. Those fixes were
verified rather than reverted/reimplemented. Default-off property is unchanged;
its stale comment now describes the implemented checkout gate.

### Defect found / production correction

The new cancellation-during-dispatch regression failed with two calls to
cancelPaymentLink for the same successfully revoked link: dispatch calls
expireAndRevoke, then checkout's status calls it again. Retained forensic IDs alone
were incorrectly treated as an outstanding revocation. WebCheckoutService now
requires both gateway_link_id AND an uncleared gateway_link_url to retry. Success
clears only the URL; failure preserves both for retry. Polling never creates a link,
never infers payment from revoke outcome, and never returns a cancelled URL. Late
signed money still goes to review without reviving the cancelled order. Also
normalized existing safeUrl indentation; no allowlist or money guard was relaxed.

### Additional tests (WebCheckoutHttpTest 33 -> 38)

- Acceptance before-commit database failure: safe 503 UNAVAILABLE; no attempt,
  order, payment or provider call; original cart and whole-request retry preserved.
- Provider-response persistence before-commit failure: existing attempt UNKNOWN,
  no partially saved link IDs, no redispatch on replay; verified bound webhook
  reconciles the original payment/order to PAID.
- Real PostgreSQL capacity mutex timeout: safe 503 and no partial acceptance;
  whole request can retry once the competing transaction releases its lock.
- Cancellation during provider create: no payable URL/order resurrection, provider
  cancellation outside DB transaction and no duplicate successful revoke on polling;
  signed late payment becomes REVIEW_REQUIRED with order still CANCELLED.
- Failed expiry revoke: retains URL/IDs for retry; success clears URL, retains IDs,
  and subsequent polling performs no additional cancellation.
- Expanded the existing unsafe URL regression to 14 forms: HTTP, suffix host,
  credentials, nonstandard port, fragment, javascript, unrelated host, hostless
  hierarchical/opaque HTTPS, schemeless, trailing-dot host, encoded authority,
  backslash authority confusion and control character. All remain UNKNOWN with no
  URL exposure/retry of link creation.

The checkout test context adds a 20-second PostgreSQL statement timeout. Its
existing HTTP/race waits and shared pool bounds remain intact. No production
timeout or invariant was weakened, and no test was disabled/skipped.

### Execution history / diagnostics (do not mistake earlier runs for final)

Initial shared-terminal runs were interrupted (batch termination prompts), and an
attempt to use a nested isolated VS Code task could not resolve that task. A
non-clean incremental run encountered editor/stale class output (AuthCrypto and,
later, StorefrontController resolved without their package). Clean Maven rebuilt
the actual sources; no source/package workaround was made.

An initial new beforeCommit fault incorrectly threw TransactionSystemException,
which claims a commit-manager ambiguity rather than an ordinary callback failure.
JDK jstack showed the test main blocked deleting its fixture customer; local
pg_stat_activity proved an idle-in-transaction acceptance INSERT held that lock.
Stopped only the stalled test JVM, allowing PostgreSQL to release its transactions.
Corrected the synthetic fault to CannotAcquireLockException in beforeCommit, which
exercises normal rollback and the real safe DataAccessException mapping. This is
a before-commit rollback test, NOT an acknowledged-commit-loss chaos guarantee.

The next full clean suite completed 347 tests: exactly one failure, the duplicate
revoke defect above. The assertion was NOT weakened; production was fixed and a
separate retry/success regression added. Final full clean run completed 348 tests,
all green. Temporary task/log attempts are not part of the implementation.

### Final exact evidence

- System Maven: C:/Program Files (x86)/Maven/mvn/bin/mvn.cmd, `-B -q clean test`;
  JAVA_HOME=C:/Program Files/Java/jdk-17.0.20.1. No wrapper/style.color/Docker.
- Surefire: **45 suites, 348 tests, 0 failures, 0 errors, 0 skipped**. Key suites:
  WebCheckoutHttpTest **38**, WebCartHttpTest **8**, WebCheckoutDisabledTest **1**,
  CapacityBoundaryTest **18**, WebWebhookSafetyTest **16**,
  CutoffTransactionSafetyTest **17**. All other 39 suites also passed, including
  legacy WA, credit, commercial, subscription, payment-link and signed-webhook
  regressions. Reports are under target/surefire-reports; final test JVM exited.
- Real local PostgreSQL **18.3**, localhost:5432/tranche_bakery_test; Flyway
  validated **49 migrations**, current schema V49. No live payment/OTP/WhatsApp.
- Editor diagnostics: no errors/warnings in modified checkout source/test and
  application properties; git diff --check passed (existing line-ending notices).
- Existing runtime warnings remain: bundled Flyway's certified PG ceiling is 17,
  explicit Hibernate dialect, OSIV, JVM instrumentation and fixture failure logs.
  They are not test failures and were not hidden/suppressed.

### Security assessment / remaining explicit limits

Reviewed cookie-only ownership, exact Origin and CSRF enforcement, opaque response
shape and safe error mapping; complete fresh quote/token/customer/cart/fulfillment
binding and immutable WEB order; capacity-first lock ordering across web/WA/admin/
cutoff/commercial/subscription writers and rollback-safe WA re-prompt; durable
idempotency/CREATING/UNKNOWN with no automatic replacement; exact HTTPS host
allowlist and no redirect proof; signed paise/INR/payment/link/reference validation,
early webhook binding, late-money review and WEB screenshot/credit exclusion;
settlement rollback and notification resource cleanup. No further concrete
checkout defect was identified in this bounded review after the revoke correction.

Credit-positive/zero-payable remains deliberately support-required; WEB never
reserves/consumes credit. This is NOT a redesign/certification of legacy credit
reservations. Commercial/prepaid subscription manual capacity overrides remain
their existing policy, serialized rather than newly capped. UNKNOWN reconciliation
is manual; no new operator UI. Notifications are post-commit best effort, not a
durable exactly-once outbox. Existing legacy/subscription sends may still run in
their own transactions; no blanket latency guarantee was claimed.

No live-provider compatibility/configuration, browser UX, production load/pen-test,
physical crash, total DB outage or acknowledged-commit/network-loss chaos test was
performed. Parent retains frontend/E2E/rollout and operational reconciliation
decisions. Do not enable checkout merely because these backend tests are green.

### Final workspace hygiene note

Final verification confirms 348/348 green, diff-check exit 0 and no diagnostics in
the five source/test/config/documentation files touched by this continuation.
The host reported successful deletion of temporary .vscode/tasks.json and
target-t006-validation.log, but subsequent filesystem/git checks still show both
untracked. Three deletion attempts were not repeated further. They are diagnostic
artifacts, not required implementation files; parent should remove/exclude them
before any later packaging. No bundle or packaging was performed here. Existing
dirty backend changes were preserved, not reset or attributed to this pass.

## Final reviewer blocker: legacy order link revocation — 2026-10-26

Root cause: OrderService.revokePaymentLink called Razorpay synchronously inside
cart-edit, customer cancellation and merge transactions while their PostgreSQL
capacity advisory/order locks were still retained. CutoffJob already fetched and
cancelled outside its short capacity transaction; its fetch needed no relocation.

Changes in this pass:
- V51 adds payment_link_revocations, a transactional outbox/fence keyed by payment
  and captured provider link. Mutation and fence commit/rollback atomically.
- OrderService keeps its original public callers and REQUIRED atomicity using
  TransactionTemplate. Direct callers retry only after execute returns AND no
  ambient transaction/synchronization remains. Joined callers leave the durable
  work for their owner or scheduler, never suspending a lock-owning transaction.
- FlowEngine drains after its transaction resource cleanup, before payment effects.
  SendPaymentQrAction retries on prompts and checks the fence in its short shared
  persistence boundary: no old URL reuse, new claim/create, QR or credit bypass.
- PaymentLinkRevocations performs cancel (and, on ambiguous failure, a fresh
  cancelled/expired-state check) outside transactions. It then atomically clears
  only the matching pending payment ID/order/link and removes that exact fence in
  a short transaction without acquiring the global capacity mutex. Failure retains
  the fence and binding; the scheduled retry also recovers restart/cancelled-order
  cases. There is no TTL permission to create a replacement.
- CutoffJob uses the same durable fence in its existing decision transaction and
  the same post-cleanup retry path; its network fetch remains outside capacity.

Regressions added to WhatsappPaymentDispatchTest (not executed here): blocking
cart edit and customer cancellation permit independent capacity acquisition while
the provider is blocked; the cart-edit test also acquires the same order row NOWAIT.
Separate tests cover replacement versus captured bindings, rollback-only and
before-commit failure with zero provider I/O, failed revocation across repeated
prompts followed by successful retry, same-amount fenced URL non-reuse, atomic
merge/source cancellation before revocation, and ambiguous-outcome recovery.
Existing cancellation/cutoff/merge regressions and their assertions are retained.

Exact files changed in this pass:
- src/main/java/com/tranche/bakery/order/OrderService.java
- src/main/java/com/tranche/bakery/order/CutoffJob.java
- src/main/java/com/tranche/bakery/flow/FlowEngine.java
- src/main/java/com/tranche/bakery/flow/actions/SendPaymentQrAction.java
- src/main/java/com/tranche/bakery/payment/PaymentLinkRevocations.java (new)
- src/main/resources/db/migration/V51__payment_link_revocations.sql (new)
- src/test/java/com/tranche/bakery/WhatsappPaymentDispatchTest.java
- docs/storefront/checkout-progress-details.md

Validation: editor diagnostics report no errors in the changed Java/SQL files;
no terminal commands, build, tests or external
provider calls were run, as explicitly requested. Runtime green is NOT claimed.
No frontend edits, provider implementation changes, push, bundle or lifecycle calls.
Parent owns execution of the focused regression suites and full backend suite.