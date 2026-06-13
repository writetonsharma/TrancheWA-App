# WhatsApp Ordering MVP - High-Level Requirements

## Goal

Create a simple WhatsApp-based ordering workflow for Tranche Bakery that lets customers place structured orders without messy free-text chats or AI-driven conversations.

The first version should focus on a reliable, low-cost MVP:

- Option-based WhatsApp ordering
- Order history stored in a database
- Cutoff-time handling
- UPI payment screenshot collection
- Manual or semi-automated payment verification
- Basic admin visibility for confirmed orders

## Customer Entry Point

The workflow starts only when the customer sends:

```text
hi
```

Messages other than `hi` should not start a new order workflow. If a customer is already inside an active workflow, their response should be interpreted based on the current step.

## Main Menu

After the customer sends `hi`, the bot should show simple options:

1. Order
2. Feedback
3. Hours
4. Location
5. Start over

The experience should stay deterministic and menu-driven. No general-purpose AI chat is required for the MVP.

## Ordering Flow

The ordering flow should guide the customer through structured choices:

1. Select `Order`
2. Choose product category, such as:
   - Loaves
   - Rolls
   - Pastries
   - Specials
3. Choose item
4. Choose quantity
5. Choose pickup or delivery, if delivery is supported
6. Show order summary
7. Ask customer to confirm
8. Move order to payment step

Customers should be able to start over during the flow.

## Cutoff Handling

If an order is placed after the configured cutoff time, initially assumed to be 6 PM, the bot should warn the customer before confirmation.

Example:

```text
Orders after 6 PM will be prepared for the next available bake day. Do you want to continue?
```

The customer should be able to either continue or cancel/start over.

## Payment Flow

The MVP should use UPI payment with screenshot upload.

Flow:

1. Bot shows final order amount.
2. Bot sends UPI ID or QR payment instruction.
3. Customer pays outside WhatsApp.
4. Customer uploads payment screenshot in WhatsApp.
5. App stores the screenshot reference against the order.
6. App attempts to read payment details using OCR or vision.
7. If payment confidence is high, mark payment as screenshot-verified.
8. If confidence is low, mark payment as review required.
9. Confirm order only after successful payment verification or manual approval.

Payment screenshot checks should include:

- Amount matches order total
- Payment status appears successful
- Recipient name or UPI ID matches the bakery
- Screenshot timestamp/date appears recent
- Transaction/reference ID has not already been used

The MVP may start with manual review and add OCR automation later.

## Feedback Flow

If the customer selects `Feedback`:

1. Bot asks the customer to type their feedback.
2. App stores the feedback with customer phone number and timestamp.
3. Bot replies with a thank-you message.

Example:

```text
Thank you for your feedback. We will get back to you if needed.
```

## Order Statuses

Initial order statuses:

- `draft`
- `pending_confirmation`
- `pending_payment_screenshot`
- `payment_screenshot_received`
- `payment_screenshot_verified`
- `payment_review_required`
- `confirmed`
- `cancelled`
- `completed`

## Suggested Database Entities

The first database design should support future website, login, order history, admin dashboard, and payment gateway integration.

Suggested entities:

- `customers`
- `orders`
- `order_items`
- `payments`
- `payment_screenshots`
- `feedback`
- `whatsapp_conversations`
- `menu_categories`
- `menu_items`

## Admin Requirements

The first admin view can be simple and internal-only.

It should show:

- Today's confirmed orders
- Tomorrow's confirmed orders
- Orders needing payment review
- Customer name/phone
- Ordered items and quantities
- Pickup or delivery details
- Payment status
- Order status

## Non-Goals for MVP

The MVP should not include:

- Customer login
- Dynamic public website ordering
- Loyalty points
- Subscription plans
- Full AI chat
- Full payment gateway integration
- Complex inventory management

These can be added after the WhatsApp ordering flow proves useful.

## Future Enhancements

Likely next phases:

- Razorpay, Cashfree, PhonePe, or other payment gateway integration
- Automated payment webhooks
- Customer order history on website
- Admin dashboard
- Dynamic menu management
- Repeat order shortcut
- Preorder calendar
- Delivery slot management
- Bake-list generation
