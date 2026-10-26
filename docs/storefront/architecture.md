# Storefront architecture and first-batch contracts

One Spring Boot backend owns money and identity. Static HTML remains the public shell. New browser modules progressively enhance it only when explicitly enabled. WhatsApp is not modified in this batch.

## Identity boundary

API namespace `/api/**`; separate customer security from `/admin/**`. Default `bakery.web.enabled=false`. Explicit configured origins only; same-origin supported. Authenticated responses and quotes are private/no-store. WhatsApp OTP uses existing Cloud API transport and a separately approved Authentication template; never a Utility substitution. No OTP transport enabled or real message sent during tests.

Auth endpoints: GET `/api/session` obtains current customer projection and CSRF bootstrap; POST `/api/auth/challenge` accepts phone; POST `/api/auth/verify` accepts challengeId/code; POST `/api/auth/logout` ends browser login. A secure HttpOnly browser cookie authenticates requests; credentials and CSRF accompany unsafe methods. No exposed customer phone query parameters for identity. Durable hashed challenge/session material with expiration and throttling; require attempt binding to initiating browser. User-visible responses never contain OTP. Customer creation must not call inbound WhatsApp helpers or grant marketing consent.

## Commerce read foundation

GET `/api/catalog`: active products with numeric database identifiers (never display-name request identity), category, description, optional weight, server prices and published offers. POST `/api/quote`: product IDs, integer quantities, optional date; returns server totals, line prices, safe availability problems and checkoutable/valid state. Authentication resolves only through verified customer session; anonymous is allowed for public quotes. Input bounds, active product/day checks and no internal capacity disclosure. Reuse current pricing by extracting a pure shared pricing component from existing OrderService, preserving all existing paths.

No web-order/payment mutation endpoints in this batch. Basket is device-local only and displayed as preview, not an order. Later durable Cart aggregate remains distinct from legacy conversational DRAFT orders and from subscription selections.

## Browser integration

Separate scripts/styles from existing galleries and product detail logic. Explicit opt-in configuration, no assumed live API DNS. When disabled, no requests or UI takeover. When enabled, preserve public content and show a clearly labelled preview drawer; no fake checkout, payment or order confirmation. Unknown product mappings are not orderable; offline or stale quote disables progression. Escape/focus trap/focus restore/backdrop/reduced motion and status announcements. Local storage contains IDs/quantities only and handles malformed or unavailable storage.

## Deployment gate

Target topology: browser uses same-origin /api/** on the Vercel website, rewritten to Railway. No second commerce backend. Preview deployments must not silently use production APIs. Personalized responses must bypass CDN caching; validate Set-Cookie, CSRF, Origin, scheme/host forwarding and trusted client-IP handling in staging before enabling delivery. Do not trust arbitrary forwarded headers from public requests.

Meta account Authentication eligibility/template and fees, public API domain, HTTPS cookie topology, configured origin, browser CORS/CSRF validation and later checkout/pilot are required before public enablement. Cross-site cookie auth is not silently presumed supported. No production cutover in this batch.

## Authorized live-test connection (2026-10-26)

The user replaced the separate-staging requirement for preview/login with a
controlled live-site test after deployment. Vercel now has six exact rewrites:
catalog, session, quote, auth/challenge, auth/verify and auth/logout, all preserving
their /api paths at https://tranchewa-app-production.up.railway.app. Every rule
matches only www.tranchebakery.in or tranchebakery.in. No catch-all API/admin/
webhook/payment route is proxied. API no-store/CDN no-store and disabled external
rewrite caching are explicit; no second commerce backend or frontend secret.

Browser configuration enables only exact HTTPS production origins, never local or
Vercel preview domains by default. No global forwarded-header trust is introduced;
remoteAddr-based throttles may share a proxy bucket during this low-volume test.
Actual visitor-IP attribution remains unproven and must be resolved before wider
rollout, not bypassed by trusting public X-Forwarded-For values. Full instructions
are in the website storefront/DEPLOYMENT.md. Hosted transport/cookie/cache checks
still precede user-initiated real OTP. This is not a checkout/payment cutover.