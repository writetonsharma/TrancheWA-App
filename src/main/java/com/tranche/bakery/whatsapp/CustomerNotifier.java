package com.tranche.bakery.whatsapp;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Component;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.receipt.ReceiptService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Central customer-notification helper for admin/job-triggered messages that may land outside
 * WhatsApp's 24h customer-service window. WhatsApp ACCEPTS out-of-window free-form sends (HTTP 200)
 * and only reports the 131047 failure later via the status webhook, so we can't rely on the send
 * response. Instead we decide up front from the customer's last inbound time: inside the window we
 * send the warm free-form text; outside it we send the matching approved Utility template.
 * In-session flow replies never come through here — they are always in-window.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CustomerNotifier {

    private final WhatsAppClient whatsAppClient;
    private final ReceiptService receiptService;

    static final String T_CONFIRMED = "order_confirmation";
    static final String T_IN_BAKING = "order_in_baking";
    static final String T_OUT_FOR_DELIVERY = "order_out_for_delivery";
    static final String T_DELIVERED = "order_delivered";
    static final String T_CANCELLED = "order_cancelled";
    static final String T_UPDATE = "order_update";
    static final String T_PAYMENT_REMINDER = "payment_reminder";
    static final String T_SUB_CONFIRMED = "subscription_confirmed";
    static final String T_SUB_RENEWAL = "subscription_renewal";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM");

    // WhatsApp's window is 24h from the customer's last message; use a 30-min safety margin so a
    // near-boundary free-form send can't silently fail. When unsure, prefer the (always-deliverable) template.
    private static final Duration SESSION_WINDOW = Duration.ofHours(23).plusMinutes(30);

    /**
     * Payment verified: inside the window, sends the receipt PDF + a warm confirmation; outside it,
     * sends the order_confirmation template whose header carries the same receipt PDF (one message).
     */
    public void orderConfirmed(Order order) {
        String phone = phone(order);
        if (phone == null) return;
        String ref = ref(order);
        String date = deliveryDate(order);
        ReceiptService.ReceiptMedia media = receiptService.prepare(order);

        if (withinWindow(order)) {
            if (media != null) {
                whatsAppClient.sendDocument(phone, media.mediaId(), media.filename(),
                        "Here is your receipt. Thank you for ordering from Tranché Bakery.");
            }
            String confirmText = "✅ *Payment verified — order confirmed!*\n\n" +
                    "Order *" + ref + "* is confirmed." +
                    (order.getDeliveryDate() != null
                            ? " We'll deliver on *" + date + "* in the morning."
                            : " We'll confirm the delivery morning with you shortly.") +
                    "\n\nThank you for ordering from Tranché Bakery. 🥖";
            whatsAppClient.sendText(phone, confirmText);
        } else if (media != null) {
            whatsAppClient.sendTemplateWithDocument(phone, T_CONFIRMED, media.mediaId(), media.filename(),
                    List.of(firstName(order), ref, date));
        } else {
            log.warn("Order {} confirmation not delivered: outside 24h window and no receipt available to carry the template.", ref);
        }
    }

    public void orderInBaking(Order order) {
        String phone = phone(order);
        if (phone == null) return;
        String ref = ref(order);
        String date = deliveryDate(order);
        if (withinWindow(order)) {
            String text = "🔥 *Great news — your order is being baked right now!*\n\n" +
                    "Order *" + ref + "* is in the oven. " +
                    (order.getDeliveryDate() != null
                            ? "We'll deliver on *" + date + "* in the morning."
                            : "We'll deliver tomorrow morning.") +
                    " 🥖";
            whatsAppClient.sendText(phone, text);
        } else {
            whatsAppClient.sendTemplate(phone, T_IN_BAKING, List.of(firstName(order), ref, date));
        }
    }

    public void orderOutForDelivery(Order order) {
        String phone = phone(order);
        if (phone == null) return;
        String ref = ref(order);
        if (withinWindow(order)) {
            whatsAppClient.sendText(phone, "🚚 *Out for delivery!*\n\n" +
                    "Your order *" + ref + "* is on its way and will reach you this morning. 🥖");
        } else {
            whatsAppClient.sendTemplate(phone, T_OUT_FOR_DELIVERY, List.of(firstName(order), ref));
        }
    }

    public void orderDelivered(Order order) {
        String phone = phone(order);
        if (phone == null) return;
        String ref = ref(order);
        if (withinWindow(order)) {
            whatsAppClient.sendText(phone, "✅ Your order *" + ref + "* has been delivered! " +
                    "Thank you for choosing Tranché Bakery. We hope you enjoy it! 🥖\n\n" +
                    "Send *hi* to place a new order anytime.");
        } else {
            whatsAppClient.sendTemplate(phone, T_DELIVERED, List.of(firstName(order), ref));
        }
    }

    /** reason may be null for a discretionary admin cancel. */
    public void orderCancelled(Order order, String reason) {
        String phone = phone(order);
        if (phone == null) return;
        String ref = ref(order);
        if (withinWindow(order)) {
            String text = "Your order *" + ref + "* has been cancelled." +
                    (reason != null && !reason.isBlank() ? " " + reason : "") +
                    "\n\nIf you have any questions, please message us. Send *hi* to place a new order. 🥖";
            whatsAppClient.sendText(phone, text);
        } else {
            String templateReason = (reason != null && !reason.isBlank()) ? reason : "Cancelled by the bakery.";
            whatsAppClient.sendTemplate(phone, T_CANCELLED, List.of(firstName(order), ref, clean(templateReason)));
        }
    }

    /** Manual admin message. customer may be null (unknown phone); orderRef null means no template fallback. */
    public void customerUpdate(Customer customer, String phone, String message, String orderRef) {
        if (phone == null || phone.isBlank()) return;
        if (customer != null && withinWindow(customer)) {
            whatsAppClient.sendText(phone, message);
        } else if (orderRef != null) {
            String name = customer != null ? customer.getName() : null;
            whatsAppClient.sendTemplate(phone, T_UPDATE, List.of(firstName(name), orderRef, clean(message)));
        } else {
            whatsAppClient.sendText(phone, message);
            log.warn("Manual message to {} may not deliver: outside 24h window and no recent order to reference for a template.", phone);
        }
    }

    /** Payment-screenshot reminder (job). Free-form for recent orders; template for advance ones. */
    public void paymentReminder(Order order, String freeFormText) {
        String phone = phone(order);
        if (phone == null) return;
        if (withinWindow(order)) {
            whatsAppClient.sendText(phone, freeFormText);
        } else {
            whatsAppClient.sendTemplate(phone, T_PAYMENT_REMINDER,
                    List.of(firstName(order), ref(order), deliveryDate(order)));
        }
    }

    // --- helpers ---
    private boolean withinWindow(Order order) {
        return order.getCustomer() != null && withinWindow(order.getCustomer());
    }

    private boolean withinWindow(Customer customer) {
        LocalDateTime last = customer.getLastInboundAt();
        return last != null && last.isAfter(LocalDateTime.now().minus(SESSION_WINDOW));
    }

    /** Subscription activated (may be out of window when the admin verifies payment later). */
    public void subscriptionConfirmed(Customer customer, String planName, java.time.LocalDate firstDelivery) {
        if (customer == null || customer.getPhone() == null) return;
        String phone = customer.getPhone();
        String day = firstDelivery != null ? firstDelivery.format(DATE_FMT) : "soon";
        if (withinWindow(customer)) {
            whatsAppClient.sendText(phone, "🎉 *Your " + clean(planName) + " subscription is active!*\n\n" +
                    "Your first weekly delivery is on *" + day + "*, and we'll bring fresh bakes every week. " +
                    "Thank you for subscribing to Tranché Bakery. 🥖");
        } else {
            whatsAppClient.sendTemplate(phone, T_SUB_CONFIRMED,
                    List.of(firstName(customer.getName()), clean(planName), day));
        }
    }

    /** Subscription's final delivery is near — nudge to renew (usually out of window). */
    public void subscriptionRenewal(Customer customer, java.time.LocalDate lastDelivery) {
        if (customer == null || customer.getPhone() == null) return;
        String phone = customer.getPhone();
        String day = lastDelivery != null ? lastDelivery.format(DATE_FMT) : "soon";
        if (withinWindow(customer)) {
            whatsAppClient.sendText(phone, "Your Tranché subscription's final delivery is on *" + day + "*. " +
                    "Reply *renew* to continue for another few weeks of fresh bakes. 🥖");
        } else {
            whatsAppClient.sendTemplate(phone, T_SUB_RENEWAL,
                    List.of(firstName(customer.getName()), day));
        }
    }

    private String phone(Order order) {
        return (order.getCustomer() != null) ? order.getCustomer().getPhone() : null;
    }

    private String ref(Order order) {
        return order.getOrderNumber() != null ? order.getOrderNumber() : "#" + order.getId();
    }

    private String deliveryDate(Order order) {
        return order.getDeliveryDate() != null ? order.getDeliveryDate().format(DATE_FMT) : "soon";
    }

    private String firstName(Order order) {
        return firstName(order.getCustomer() != null ? order.getCustomer().getName() : null);
    }

    private String firstName(String name) {
        if (name == null || name.isBlank()) return "there";
        return name.trim().split("\\s+")[0];
    }

    // WhatsApp rejects template params containing newlines or 4+ consecutive spaces.
    private String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }
}
