# Storefront foundation + opt-in preview checkpoint

## Scope delivered

Durable WhatsApp OTP/session foundation, exact-origin + CSRF API security,
cookie-derived customer quote identity, shared server pricing, and dedicated web
activity protection against ghost-customer cleanup. Additive Flyway V46/V47.

Static website adds a production-origin-only preview enhancement: exact catalogue
bindings, quantity controls, device-only basket, animated accessible right drawer,
server quote/date preview and optional WhatsApp login/logout. No parked storefront
implementation was used. Existing public WhatsApp ordering remains unchanged.

## Verified locally

- Clean Maven suite: 250 tests, 0 failures/errors/skips, local PostgreSQL.
- Real loopback Tomcat smoke covers foundation routes/cookies/CSRF with mocked
  provider delivery. It is not a real Meta or hosted reverse-proxy test.
- Frontend: 29 Node model/API/view/routing tests, syntax and whitespace checks.
- Browser with fake loopback catalogue/auth/quote API: disabled zero API requests
  and no launcher; 20 mapped public products; quantities/persistence/reload; quote
  and date; fake OTP + login/logout + CSRF rotation; personalised credit preview;
  clearing totals on logout/offline; close/backdrop/Escape/focus/Tab wrap;
  reduced-motion; 1440/390/320px layouts, no horizontal overflow.
- Source review: corrected CSRF header to X-XSRF-TOKEN, quantity live status and
  explicit Escape handling; follow-up read-only review found no concrete blockers.

## Transfer and user-approved live preview test

Root base64 bundles carry application and website changes separately. User unpacks,
reviews, commits and pushes on the other machine. Do not push here, reset local
work, or include the temporary preview server/fixtures. Wait for explicit pushed
confirmation before syncing only those repos and re-running regression tests.

On 2026-10-26 the user explicitly authorized live-site preview/login testing after
they deploy, replacing the separate-staging requirement for this bounded slice.
Website config now enables on exact HTTPS www/apex production origins; Vercel
rewrites six exact customer endpoints to the existing Railway backend, host-guarded
and uncached. Backend defaults stay off. Additive migrations may execute while
endpoints are off. Follow the website's storefront/DEPLOYMENT.md: configure secret
and origins privately, enable API, verify hosted transport, then enable OTP sending.
No actual push, deployment or real OTP has occurred here. Checkout is not enabled.

## Hosted live-test checks still pending

- Explicit allowed production origins. Same-origin Vercel /api proxy to Railway;
  no preview-to-production fallback. No separate staging setup is required for this
  user-approved manual preview/login test.
- HTTPS/Secure/HttpOnly/SameSite cookies, cookie forwarding, Origin/CSRF checks,
  no-store at browser/proxy/CDN, host/scheme and trusted-client-IP behaviour.
- Strong authentication secret provisioned outside source and bundles, delivery
  flag separately approved, template website_login_otp language en (user reports
  Active), real consented test delivery and rejection/rate-limit behaviour.
- Integrated browser against the real hosted backend, concurrent identity/quote
  transitions, actual network failure and multi-tab tests, screen-reader/device
  accessibility and zoom audit. Mocked local checks do not replace these.

## Latest connection validation — 2026-10-26

- Repeated clean backend suite: 250 tests, no failures/errors/skips, 39 reports.
- Exact-origin activation, six host-guarded rewrite paths and no-store controls:
  five added Node tests; 29 total passing. No Java source changed for this follow-up.
- Fresh local browser without injection: config false, no launcher, zero API
  requests. Explicit test-only injection against fake local APIs: 20 mapped cards,
  date/quote, fake login/logout, personalised credit and stale-total clearing.
- Read-only routing review found no concrete scoped blocker. Actual hosted
  Set-Cookie, CSRF/Origin, caching, proxy-IP and Meta delivery remain unverified.

## Next implementation task

T006: durable website baskets and transactional/idempotent retail checkout,
reusing existing Razorpay links and verified webhooks. Customer history and
subscriptions follow. WhatsApp commerce writes must not be disabled until a
working website pilot and controlled cutover are approved.