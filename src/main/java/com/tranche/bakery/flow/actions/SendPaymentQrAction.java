package com.tranche.bakery.flow.actions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.tranche.bakery.alert.AlertService;
import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.payment.Payment;
import com.tranche.bakery.payment.PaymentRepository;
import com.tranche.bakery.payment.PaymentStatus;
import com.tranche.bakery.payment.PaymentTestMode;
import com.tranche.bakery.payment.QrCodeService;
import com.tranche.bakery.payment.RazorpayService;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import com.tranche.bakery.whatsapp.WhatsAppMessage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class SendPaymentQrAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final QrCodeService qrCodeService;
    private final WhatsAppClient whatsAppClient;
    private final AlertService alertService;
    private final PaymentTestMode paymentTestMode;
    private final RazorpayService razorpayService;

    @Value("${bakery.payment.upi-id}")
    private String upiId;

    @Value("${bakery.payment.upi-name}")
    private String upiName;

    @Value("${bakery.support.whatsapp}")
    private String supportPhone;

    @Value("${bakery.order.cutoff-hour}")
    private int cutoffHour;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private String supportLine() {
        return "Trouble paying? Message or call us on " + supportPhone + ".";
    }

    /** An unpaid link for the same amount is still good — re-send it rather than mint a second one. */
    private String reusableLinkUrl(Payment payment, BigDecimal amount) {
        return payment.getStatus() == PaymentStatus.PENDING
                && "RAZORPAY".equals(payment.getProvider())
                && payment.getGatewayLinkUrl() != null
                && payment.getAmount() != null
                && payment.getAmount().compareTo(amount) == 0
                        ? payment.getGatewayLinkUrl()
                        : null;
    }

    /** CutoffJob cancels the order at the cutoff the evening before its bake day, so the link dies then too. */
    private Instant cutoffInstantFor(Order order) {
        LocalDate deliveryDate = order.getDeliveryDate();
        return deliveryDate == null ? null
                : deliveryDate.minusDays(1).atTime(cutoffHour, 0).atZone(IST).toInstant();
    }

    @Override
    public String getName() { return "SEND_PAYMENT_QR"; }

    @Override
    public void execute(ActionContext ctx) {
        String orderIdStr = ctx.contextValue("orderId");
        if (orderIdStr == null) {
            whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                    "We couldn't find your order. Send *hi* to return to the main menu.");
            return;
        }

        Order order = orderRepository.findById(Long.parseLong(orderIdStr)).orElse(null);
        if (order == null) {
            whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                    "We couldn't find your order. Send *hi* to return to the main menu.");
            return;
        }

        // Only send a payment prompt while the order is still awaiting payment. Guards against a stale
        // button (e.g. the review-message "Cancel") re-entering PAYMENT_PENDING after the Razorpay webhook
        // already confirmed the order — without this it would re-issue a payment link/QR for a paid order.
        if (order.getStatus() != OrderStatus.PENDING_CONFIRMATION) {
            String ref = order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId();
            whatsAppClient.sendText(ctx.getCustomer().getPhone(), order.getStatus() == OrderStatus.CANCELLED
                    ? "Order " + ref + " was cancelled. Send *hi* to place a new order. \uD83E\uDD56"
                    : "\u2705 Order " + ref + " is already confirmed \u2014 no further payment is needed. Send *hi* anytime. \uD83E\uDD56");
            return;
        }

        BigDecimal amount = paymentTestMode.amountFor(ctx.getCustomer().getPhone(), order.getTotalAmount());
        if (amount.signum() <= 0) {
            // Account credit fully covers this order — there's nothing to charge. Route it to admin
            // review so the existing Approve action confirms it and consumes the credit.
            order.setStatus(com.tranche.bakery.order.OrderStatus.PAYMENT_REVIEW_REQUIRED);
            orderRepository.save(order);
            String ref0 = order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId();
            whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                    "✅ *Order " + ref0 + " — fully covered by your account credit!*\n\n" +
                    "No payment needed. We'll confirm your order shortly. 🥖");
            alertService.raise("CREDIT_COVERED",
                    "Order " + order.getId() + " is fully covered by customer credit — approve to confirm.",
                    order.getId(), ctx.getCustomer().getPhone());
            return;
        }
        String orderRef = order.getOrderNumber() != null ? order.getOrderNumber() : String.valueOf(order.getId());
        // UPI/WhatsApp note accepts letters, numbers and spaces only — turn any other char into a space.
        String note = ("Tranche Bakery Order " + orderRef).replaceAll("[^A-Za-z0-9 ]", " ").replaceAll(" +", " ").trim();

        // Gateway path: a hosted payment link, auto-confirmed by the webhook. There is deliberately no
        // fall back to the QR flow — two live payment routes for one order invites a double payment.
        if (razorpayService.isGatewayMode()) {
            Payment gatewayPayment = paymentRepository.findByOrder(order).orElseGet(() -> {
                Payment p = new Payment();
                p.setOrder(order);
                return p;
            });

            String linkUrl = reusableLinkUrl(gatewayPayment, amount);
            if (linkUrl == null) {
                // Only reached when the amount changed, so the old link would collect the wrong sum.
                if (gatewayPayment.getGatewayLinkId() != null) {
                    try {
                        razorpayService.cancelPaymentLink(gatewayPayment.getGatewayLinkId());
                    } catch (Exception e) {
                        log.warn("Could not cancel superseded link {} for order {} \u2014 {}",
                                gatewayPayment.getGatewayLinkId(), order.getId(), e.getMessage());
                    }
                }
                // reference_id must be unique per link, so a re-issue cannot reuse the plain order ref.
                String referenceId = gatewayPayment.getGatewayLinkId() == null
                        ? orderRef : orderRef + "-" + Instant.now().getEpochSecond();
                try {
                    RazorpayService.PaymentLink link = razorpayService.createPaymentLink(
                            amount, note, referenceId,
                            ctx.getCustomer().getName(), ctx.getCustomer().getPhone(),
                            java.util.Map.of("kind", "ORDER", "orderId", String.valueOf(order.getId())),
                            cutoffInstantFor(order));

                    // Recorded before the customer can pay, so the webhook has a local row to reconcile
                    // against and a figure to amount-verify the payment against.
                    gatewayPayment.setProvider("RAZORPAY");
                    gatewayPayment.setGatewayLinkId(link.id());
                    gatewayPayment.setGatewayLinkUrl(link.shortUrl());
                    gatewayPayment.setAmount(amount);
                    gatewayPayment.setStatus(PaymentStatus.PENDING);
                    paymentRepository.save(gatewayPayment);
                    linkUrl = link.shortUrl();
                    log.info("Created Razorpay payment link {} for order {}", link.id(), order.getId());
                } catch (Exception e) {
                    log.error("Razorpay link failed for order {}: {}", order.getId(), e.getMessage());
                    alertService.raise("PAYMENT_LINK_FAILED",
                            "Could not create a Razorpay link for order " + order.getId() + ": " + e.getMessage(),
                            order.getId(), ctx.getCustomer().getPhone());
                    whatsAppClient.sendText(ctx.getCustomer().getPhone(), String.format(
                            "We couldn't generate the payment link for order %s just now.%n%n%s",
                            orderRef, supportLine()));
                    return;
                }
            }

            whatsAppClient.sendText(ctx.getCustomer().getPhone(), String.format(
                    "*Order %s \u2014 \u20b9%.2f*%s%n%n\uD83D\uDC49 Tap to pay securely (UPI, card or netbanking):%n%s%n%n"
                            + "Your order confirms automatically the moment payment is received. \uD83E\uDD56%n%n%s",
                    orderRef, amount, savingsLine(order), linkUrl, supportLine()));
            try {
                whatsAppClient.sendButtons(ctx.getCustomer().getPhone(),
                        "Need to cancel this order? You can do so below.",
                        List.of(new WhatsAppMessage.Button("cancel_" + order.getId(), "Cancel Order")));
            } catch (Exception ignore) { /* best effort */ }
            return;
        }

        log.info("Sending payment QR for order {} amount {}", order.getId(), amount);

        try {
            Payment payment = paymentRepository.findByOrder(order).orElseGet(() -> {
                Payment p = new Payment();
                p.setOrder(order);
                p.setUpiId(upiId);
                return p;
            });
            payment.setAmount(amount);
            paymentRepository.save(payment);

            byte[] qrPng = qrCodeService.generateUpiQrPng(upiId, upiName, amount, note);
            log.info("QR PNG generated, {} bytes", qrPng.length);
            if (qrPng.length == 0) throw new IllegalStateException("QR PNG is empty — AWT rendering failed");

            payment.setQrImageData(qrPng);
            paymentRepository.save(payment);

            String mediaId = whatsAppClient.uploadMedia(qrPng, "payment-qr.png");
            log.info("Media uploaded, mediaId={}", mediaId);
            String caption = String.format(
                    "*Order %s — ₹%.2f*%s%n%nScan the QR code above with any UPI app, or pay manually to *%s*.%n%n📸 *Important — after paying, share the payment screenshot here.* That's the final step to confirm your order.",
                    order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId(),
                    amount, savingsLine(order), upiId);
            whatsAppClient.sendImage(ctx.getCustomer().getPhone(), mediaId, caption);
            log.info("sendImage called for order {}", order.getId());
        } catch (Exception e) {
            log.error("Payment QR flow failed for order {}: {}", order.getId(), e.getMessage(), e);
            alertService.raise("QR_FAILURE",
                    "Payment QR failed for order " + order.getId() + ": " + e.getMessage(),
                    order.getId(), ctx.getCustomer().getPhone());
            whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                    String.format("*Order %s — please complete payment of ₹%.2f.*%n%n" +
                            "*UPI ID:* %s%n%n📸 *Important — after paying, share the payment screenshot here.* That's the final step to confirm your order.",
                            order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId(),
                            amount, upiId));
        }

        try {
            whatsAppClient.sendButtons(ctx.getCustomer().getPhone(),
                    "Need to cancel this order? You can do so below.",
                    List.of(new WhatsAppMessage.Button("cancel_" + order.getId(), "Cancel Order")));
        } catch (Exception e) {
            log.error("Failed to send cancel button for order {}: {}", order.getId(), e.getMessage());
        }
    }

    // "💰 You're saving ₹X on this order!" — surfaced on the payment prompt to encourage completion.
    private String savingsLine(Order order) {
        BigDecimal disc = order.getDiscountAmount() != null ? order.getDiscountAmount() : BigDecimal.ZERO;
        BigDecimal batch = order.getBatchDiscountAmount() != null ? order.getBatchDiscountAmount() : BigDecimal.ZERO;
        BigDecimal savings = disc.add(batch);
        return savings.signum() > 0
                ? String.format("%n\uD83D\uDCB0 *You're saving \u20B9%.0f on this order!*", savings)
                : "";
    }
}
