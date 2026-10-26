# Storefront implementation constraints

- Preserve production and existing local work. No push, reset, deployment or production credentials/API calls.
- Do not restore, copy or use parked implementation as the design basis.
- Use current Java 17/Spring Boot/PostgreSQL and existing static frontend; no mandatory SPA or new commerce backend.
- Fail closed on auth, unavailable backend, stale quotes and payment uncertainty. A quote reserves neither stock nor money.
- All new customer APIs need explicit security; admin identity must never authenticate as a customer.
- No OTPs, access tokens, session cookies or customer data in logs/bundles/browser localStorage.
- Preserve HTML product content, photography, olive visual identity and WhatsApp fallback until actual launch.
- Backend feature flags default off. Meta Authentication approval and production origin/domain configuration are external launch prerequisites.

## Approved live-test exception — 2026-10-26

The user explicitly chose the live website instead of a separate staging site for
cart-preview and WhatsApp OTP/login testing after they deploy. Website activation
on the two exact HTTPS production origins and host-restricted Vercel rewrites are
authorized. This supersedes the earlier frontend-off/staging-only restriction for
this bounded test, not for checkout/payments or WhatsApp commerce cutover. Backend
runtime flags still default false; secrets stay in Railway; no automatic real OTP
sends, pushes, resets or deployments from this machine. Hosted checks and manual
OTP testing must be recorded separately from local mocked evidence.