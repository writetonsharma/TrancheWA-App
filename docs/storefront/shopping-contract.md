# Local-first shopping snapshot contract

Backend-only addition, 2026-10-27. No schema migration, frontend change, deployment,
OTP, payment or order creation. Existing catalogue/quote JSON and shared pricing,
WhatsApp and subscription semantics are unchanged.

## HTTP and identity

`POST /api/shopping`, `Content-Type: application/json`, credentials included.
Requires the existing CSRF cookie/header pair (`TRANCHE_WEB_CSRF`, `X-XSRF-TOKEN`);
obtain the token through the existing auth/session bootstrap, not by reading the
HttpOnly cookie. Existing configured Origin allowlist/CORS/ApiGuard apply.
`bakery.web.enabled=true` enables public preview; checkout need not be enabled.
Disabled API returns 404; rejected Origin or CSRF returns 403 before processing.
All responses, including filter failures, have `Cache-Control: no-store, private`
and API filter responses also have `Pragma: no-cache`.

Anonymous browsers receive public prices. Only the existing verified opaque,
browser-bound customer session personalizes prices and eligibility. Invalid or
expired cookies fall back to anonymous exactly as on quote; claimed customer IDs,
phone, headers, admin principals and query parameters never select a customer.
Session-store database failures fail closed with 503. No customer data is returned.

### Exact request

```json
{}
```

or `{"date":null}` or `{"date":"2026-10-30"}`. The date is an example, not a
permanent supported date. Required body must be an object; only `date` is accepted.
Strings must be real ISO `yyyy-MM-dd` dates, and must belong to this customer's
seven currently upcoming base dates. Missing/null date means unscoped products
and no batch calculation. Wrong shape/extra fields return HTTP 400
`INVALID_REQUEST`; wrong date type/format/day/horizon returns 400 `INVALID_DATE`.
Malformed JSON returns the existing sanitized 400 error shape. Application errors:
`{"valid":false,"problems":[{"code":"INVALID_DATE","message":"..."}]}`.
Security filters can return empty error bodies; do not assume every error is JSON.

### Exact success DTO shape

Illustrative values only; every key below is always present. Arrays contain zero
or more products/eligible IDs and exactly seven dates; nullable values are JSON
null, not omitted. All amounts are JSON numbers in INR.

```json
{
  "retailLimit": 5,
  "date": null,
  "pricingMode": "SINGLE_ITEM_ESTIMATE",
  "items": [
    {
      "id": 1,
      "name": "Example loaf",
      "category": "Breads",
      "description": null,
      "weightLabel": null,
      "listPrice": 100,
      "unitPrice": 80,
      "priceLabel": "Launch offer",
      "singleItemBatchDiscount": null
    }
  ],
  "dates": [
    {"date":"2026-10-29","maxItems":5,"itemIds":[1]},
    {"date":"2026-10-30","maxItems":3,"itemIds":[1]},
    {"date":"2026-10-31","maxItems":0,"itemIds":[1]},
    {"date":"2026-11-01","maxItems":5,"itemIds":[1]},
    {"date":"2026-11-02","maxItems":5,"itemIds":[1]},
    {"date":"2026-11-03","maxItems":5,"itemIds":[1]},
    {"date":"2026-11-05","maxItems":5,"itemIds":[1]}
  ]
}
```

- `retailLimit`: constant 5, total quantities across all products, not per product.
- `date`: normalized requested date or null.
- `pricingMode`: constant `SINGLE_ITEM_ESTIMATE`; no exact basket total is exported.
- `items`: active products in active categories, ordered as catalogue. Unscoped
  returns all; scoped returns only products eligible for that date. Full dates
  still retain eligible products; eligibility does not reserve capacity.
- `unitPrice`: **single quantity-one product price before batch, delivery and
  credit**, from `CartPricingService.price`: list subtotal minus winning discount.
  Includes public percentage promotion or the cheaper live item/category/global
  customer override, respecting priority, expiry and existing rounding. Do not
  interpret this as the list price used by `/api/quote` lines.
- `priceLabel`: shared winning discount label, nullable; display as text, never HTML.
- `singleItemBatchDiscount`: null without date; otherwise shared batch discount
  amount for this one product at quantity ONE on the selected date, including zero
  when inapplicable/suppressed by a live override. It is not a per-unit rule for
  larger/mixed baskets. It cannot safely be multiplied by basket quantity.
- `dates`: seven upcoming base deliverable dates (cutoff/Wednesday closure), never
  filtered by capacity or product constraints. `itemIds` independently applies
  active-product, bagel lead-time, weekend-only and verified customer F&F rules.
  `maxItems` is internal remaining capacity clamped to 0..5. Keep it internal to
  availability controls, **do not render capacity/counts**. Zero disables a date;
  do not offer adding more than `min(retailLimit, maxItems)` total units. This is
  advisory and not a reservation. True daily capacity, booked counts, raw remaining
  demand, override maps, credit balances and customer identifiers are never sent.
  Capping and finite dates reduce exposure, but finite availability inference cannot
  be eliminated. Do not repeatedly poll dates to infer other customers' activity.

## Frontend rules: no per-click server reads

Fetch `{}` at bootstrap and after login/logout/session changes, discarding the old
personalized snapshot immediately. Fetch a scoped snapshot when changing date or
explicitly refreshing availability, not when changing quantities. Local quantity
clicks may compute `sum(unitPrice * quantity)` labelled **Estimated product
subtotal — before batch, delivery, basket promotions and credit; final price at
checkout**. For an empty basket use zero. Never call this an exact subtotal, savings
or final total. Do not multiply batch previews or infer delivery/gift thresholds.
Do not offer a full estimated payable total with this DTO.

Why this deliberate limitation: the shared engine picks public discount versus
customer override on the **whole basket**, not independently for every product;
public discounts round on the whole subtotal; batch thresholds depend on booked
demand and basket quantities. For example single-item public winners mixed with
item-specific special rates may not jointly win for a mixed basket. Reusing the
shared service for single-item previews preserves semantics without exporting a
second promotion engine or a combinatorial basket lookup.

Capacity, eligibility, offers and sessions may change after the snapshot. Do not
persist personalized snapshots in public/browser caches or use one after an auth
transition/offline failure as a current price guarantee. Money computations should
use decimal-safe arithmetic. Existing cart save/prepare/final checkout remain the
authority: save on deliberate synchronization, prepare once before confirmation,
show its full quote, and submit its version/token. A declined/changed final quote
requires review; the shopping response is never permission to order or pay.

## Website-only five-item guardrail

- PUT `/api/cart`: existing parser merges duplicate IDs then rejects total >5 with
  HTTP 400 `RETAIL_LIMIT_EXCEEDED` before inserting/updating the cart. No truncation.
  Legacy oversized saved carts remain fully readable. Every save above five is
  rejected (even reduction from eight to six); reduce locally to five or fewer,
  then save with the existing optimistic version. Saved quotes/capacity stay advisory.
- POST `/api/checkout/prepare` and new POST `/api/checkout` attempts: reject >5
  with HTTP 409 `RETAIL_LIMIT_EXCEEDED` before persisting a prepared token, order,
  attempt or payment/provider dispatch. Existing date/product validity errors can
  take precedence for stale carts with no quote.
- Existing same-request idempotency replay runs BEFORE cart/retail validation.
  Previously accepted oversized attempts retain recovery/status/payment behavior,
  including `CREATING` and `UNKNOWN` attempt states (not order statuses). Never
  replace an uncertain attempt automatically.
- `/api/catalog`, `/api/quote`, WhatsApp, subscriptions and historical order pricing
  are not limited by this website-only policy. No migrations are required.