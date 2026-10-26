# Ghost cleanup / web authentication — narrow change

## Scope and decisions

- Read the current storefront spec and constitution; no parked implementation was used.
- Confirmed cleanup previously used only creation/inbound timestamps for nameless customers.
- Added V47 (V46 unchanged): nullable `customers.last_web_activity_at`, backfilled from existing
  sessions' latest activity in Asia/Kolkata, matching the application's customer timestamp zone.
- Customer entity exposes this field but excludes it from ORM updates: an ordinary stale entity
  save must not erase a newer JDBC web-activity touch.
- Successful OTP verification upserts/touches web activity atomically. Authenticated session use
  touches it transactionally. Both use monotonic activity timestamps, with explicit IST conversion.
- Cleanup requires web inactivity too and pessimistically locks candidates through deletion.
  Authentication locks customer before session rows, matching cleanup's cascade lock order.
- Inbound-window timestamps and existing marketing consent are not changed. New web-only customers
  retain the existing opt-out default.
- Decomposition verdict: atomic, limited to one cleanup/auth interaction. No scenario skill root,
  assessment, task artifact path or related skills were forwarded. Skill lookup returned none;
  Execution extension lookup returned none. No task/lifecycle artifacts were invented.

## Files changed

- Customer entity and CustomerRepository eligibility/locking.
- OtpService and CustomerSessionService.
- V47 customer web activity migration.
- GhostCustomerCleanupJobTest and new WebAuthGhostActivityTest.

## Regression tests authored

- Active nameless web-only customer survives the actual cleanup job, without inbound/consent changes.
- Old web-inactive and legacy null-web-activity ghosts are still purged.
- New and returning OTP-authenticated customers get dedicated activity; identity is preserved.
- Valid session use refreshes activity without changing inbound timestamps or marketing opt-in.
- Failed OTP, wrong browser, and expired session do not refresh activity.
- Session and OTP activity cannot move backwards when clock observations are reordered.
- Exact cutoff is preserved; one second older is eligible.
- An ordinary stale entity save cannot overwrite activity recorded since the entity was loaded.
- Auth tests use a fixed clock and explicit cutoff; job tests use whole-second timestamps with
  a multi-day safety margin, avoiding database fractional-second round-trip comparisons.

## Validation / handoff

- Editor diagnostics: no errors reported in the six affected Java files.
- Git whitespace check passed.
- No Maven/build/test execution: explicitly reserved for the parent sharing the target directory.
- Parent should run both affected test classes and existing WebAuthIntegrationTest against PostgreSQL,
  including normal Flyway startup to validate V47. Runtime lock behaviour remains unexecuted here.
- No deployment, production calls, bundles or live messages.