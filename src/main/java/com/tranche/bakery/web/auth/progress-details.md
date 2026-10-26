# T002 implementation handoff

- Research/enrichment: package-info records verified identity/schema/security/transport scope before implementation. No scenario root, task.md, assessment or progress path was supplied; storefront documents were read first. No docs owned by other workers were edited.
- Atomic verdict: one coherent OTP/customer-session boundary; no modernization Execution/hint files were supplied. Execution extension lookup reported none applicable.
- Files: new web/auth package, V46, four auth test classes; admin SecurityConfig ordering only, WhatsAppClient dedicated redacted bounded-time transport, WhatsAppMessage Authentication payload builder, application properties, pom security-test dependency.
- Not run: Maven, builds, tests, live services, outbound messages. Parent must compile and run full baseline plus new tests after parallel writers finish. No green-test claim.
- Identity contract: inject CustomerSessionService; currentCustomer(HttpServletRequest) returns Customer or null. It accepts ONLY host-only TRANCHE_WEB_SESSION + initiating TRANCHE_WEB_BROWSER opaque cookies backed by durable HMAC hashes. Do not serialize the entity. Admin Authentication/JSESSIONID never supplies customer identity. Use numeric customer ID for ownership.
- GET /api/session and POST /api/auth/verify/logout return {authenticated,customer:{id,name}|null,csrfHeaderName,csrfToken}. POST challenge {phone} returns {challengeId,expiresInSeconds:300,message}; verify input {challengeId,code}. Logout POST has no body. Bootstrap GET before POST; echo csrfToken in csrfHeaderName, credentials included. Errors are generic 400/429/503; feature off is 404, CSRF/origin violations 403; no admin redirect.
- Environment: BAKERY_WEB_ENABLED=false, BAKERY_WEB_OTP_ENABLED=false by default; BAKERY_WEB_AUTH_SECRET=random base64 >=32 decoded bytes required when enabled; BAKERY_WEB_OTP_TEMPLATE=approved Authentication template (not Utility), BAKERY_WEB_OTP_LANGUAGE=en; BAKERY_WEB_ALLOWED_ORIGINS=comma-separated exact scheme/host/port with no trailing slash or wildcard. BAKERY_WEB_COOKIE_SECURE defaults true; explicit false is local HTTP only. Existing WhatsApp credentials also required to send.
- Security limits: 5-minute OTP, 5 attempts, sends per 15 minutes: IP 20, phone 5, browser 5; verification per 15 minutes IP 100/browser 30. New challenge invalidates old browser challenges. Atomic transaction/row locks plus browser advisory lock, failure counts commit, successful verify rotates/revokes prior browser sessions. Session idle 30 minutes, absolute 7 days. All unsafe API methods CSRF-protected with plain SPA request handler. Cookies HttpOnly/Secure/SameSite=Lax, path /, no Domain. No JWT/localStorage.
- Privacy: generic challenge responses; no OTP payload logging in Authentication path; stored OTP is keyed HMAC bound to challenge/browser; session stores hash only. Direct customer insert explicitly sets marketing=false and never touches lastInboundAt. Existing identities/consent preserved.
- Parent validation: review actual reverse-proxy scheme/host handling and IP trust; rate limiter deliberately uses remoteAddr, never untrusted X-Forwarded-For. Shared-proxy IPs share limits unless trusted proxy handling is configured. Same-site HTTPS topology is required; unrelated cross-site hosts are not supported by Lax cookies. Configure no request/HTTP wire logging externally; credentials/OTPs must not be captured by infrastructure.
- External launch prerequisites: Meta approval/category/copy-code support, correct HTTPS origins and persistent strong key. Secret rotation intentionally invalidates extant auth. Future checkout endpoints must explicitly invoke currentCustomer and enforce ownership; API chain does not infer or elevate Spring principals.

## Follow-up static review (same task, no test execution)
- Added hourly auth-only retention: challenges and counters older than one day, expired/idle sessions; disabled feature performs no cleanup. No customer deletion.
- Added concurrent verify single-winner test, separate IP-limit test, service-recreation durable-limit test, and transport-disabled no-client/no-DB unit test.
- Editor diagnostics were queried, not a Maven build. They contained null-annotation warnings (including pre-existing transport warnings) and stale unresolved-method/lambda entries pointing to already updated source. Parent compile/full tests remain required; no clean build or zero-warning claim is made.
- Added local generic validation/JSON/storage error handlers so default argument-binding diagnostics do not echo submitted authentication fields. API filter override now explicitly retains inherited non-null parameter contracts.

## Exact changed-file inventory (relative to application root)
- src/main/java/com/tranche/bakery/web/auth/package-info.java
- src/main/java/com/tranche/bakery/web/auth/WebAuthSettings.java
- src/main/java/com/tranche/bakery/web/auth/AuthCrypto.java
- src/main/java/com/tranche/bakery/web/auth/AuthCookies.java
- src/main/java/com/tranche/bakery/web/auth/AuthController.java
- src/main/java/com/tranche/bakery/web/auth/OtpService.java
- src/main/java/com/tranche/bakery/web/auth/CustomerSessionService.java
- src/main/java/com/tranche/bakery/web/auth/WebApiSecurity.java
- src/main/java/com/tranche/bakery/web/auth/AuthRetention.java
- src/main/java/com/tranche/bakery/web/auth/progress-details.md
- src/main/java/com/tranche/bakery/admin/SecurityConfig.java
- src/main/java/com/tranche/bakery/whatsapp/WhatsAppClient.java
- src/main/java/com/tranche/bakery/whatsapp/WhatsAppMessage.java
- src/main/resources/db/migration/V46__web_customer_auth.sql
- src/main/resources/application.properties
- pom.xml
- src/test/java/com/tranche/bakery/web/auth/AuthCryptoTest.java
- src/test/java/com/tranche/bakery/web/auth/WebAuthIntegrationTest.java
- src/test/java/com/tranche/bakery/web/auth/WebAuthDisabledTest.java
- src/test/java/com/tranche/bakery/web/auth/AuthenticationTemplateTest.java