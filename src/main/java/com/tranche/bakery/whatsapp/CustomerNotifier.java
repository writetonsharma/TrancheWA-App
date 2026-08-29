package com.tranche.bakery.whatsapp;

import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Component;

import com.tranche.bakery.order.Order;
import com.tranche.bakery.receipt.ReceiptService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Central customer-notification helper for admin/job-triggered messages that may land
 * outside WhatsApp's 24h customer-service window. It sends the warm free-form text first;
 * if WhatsApp reports the window is closed (error 131047), it re-sends the matching approved
 * Utility template. In-session flow replies never come through here — they are always in-window.
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

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM");

    /**
     * Payment verified: sends the receipt PDF + confirmation. If the window is closed, falls back
     * to the order_confirmed template whose header carries the same receipt PDF (one message).
     */
    public void orderConfirmed(Order order) {
        String phone = phone(order);
        if (phone == null) return;
        String ref = ref(order);
        String date = deliveryDate(order);
        String confirmText = "✅ *Payment verified — order confirmed!*\n\n" +
                "Order *" + ref + "* is confirmed." +
                (order.getDeliveryDate() != null
                        ? " We'll deliver on *" + date + "* in the morning."
                        : " We'll confirm the delivery morning with you shortly.") +
                "\n\nThank you for ordering from Tranché Bakery. 🥖";

        ReceiptService.ReceiptMedia media = receiptService.prepare(order);
        if (media != null) {
            SendOutcome outcome = whatsAppClient.sendDocument(phone, media.mediaId(), media.filename(),
                    "Here is your receipt. Thank you for ordering from Tranché Bakery.");
            if (outcome == SendOutcome.WINDOW_CLOSED) {
                whatsAppClient.sendTemplateWithDocument(phone, T_CONFIRMED, media.mediaId(), media.filename(),
                        List.of(firstName(order), ref, date));
                return;
            }
            whatsAppClient.sendText(phone, confirmText);
        } else {
            SendOutcome outcome = whatsAppClient.sendText(phone, confirmText);
            if (outcome == SendOutcome.WINDOW_CLOSED) {
                log.warn("Order {} confirmation not delivered: 24h window closed and no receipt available to carry the template.", ref);
            }
        }
    }

    public void orderInBaking(Order order) {
        String phone = phone(order);
        if (phone == null) return;
        String ref = ref(order);
        String date = deliveryDate(order);
        String text = "🔥 *Great news — your order is being baked right now!*\n\n" +
                "Order *" + ref + "* is in the oven. " +
                (order.getDeliveryDate() != null
                        ? "We'll deliver on *" + date + "* in the morning."
                        : "We'll deliver tomorrow morning.") +
                " 🥖";
        if (whatsAppClient.sendText(phone, text) == SendOutcome.WINDOW_CLOSED) {
            whatsAppClient.sendTemplate(phone, T_IN_BAKING, List.of(firstName(order), ref, date));
        }
    }

    public void orderOutForDelivery(Order order) {
        String phone = phone(order);
        if (phone == null) return;
        String ref = ref(order);
        String text = "🚚 *Out for delivery!*\n\n" +
                "Your order *" + ref + "* is on its way and will reach you this morning. 🥖";
        if (whatsAppClient.sendText(phone, text) == SendOutcome.WINDOW_CLOSED) {
            whatsAppClient.sendTemplate(phone, T_OUT_FOR_DELIVERY, List.of(firstName(order), ref));
        }
    }

    public void orderDelivered(Order order) {
        String phone = phone(order);
        if (phone == null) return;
        String ref = ref(order);
        String text = "✅ Your order *" + ref + "* has been delivered! " +
                "Thank you for choosing Tranché Bakery. We hope you enjoy it! 🥖\n\n" +
                "Send *hi* to place a new order anytime.";
        if (whatsAppClient.sendText(phone, text) == SendOutcome.WINDOW_CLOSED) {
            whatsAppClient.sendTemplate(phone, T_DELIVERED, List.of(firstName(order), ref));
        }
    }

    /** reason may be null for a discretionary admin cancel. */
    public void orderCancelled(Order order, String reason) {
        String phone = phone(order);
        if (phone == null) return;
        String ref = ref(order);
        String text = "Your order *" + ref + "* has been cancelled." +
                (reason != null && !reason.isBlank() ? " " + reason : "") +
                "\n\nIf you have any questions, please message us. Send *hi* to place a new order. 🥖";
        if (whatsAppClient.sendText(phone, text) == SendOutcome.WINDOW_CLOSED) {
            String templateReason = (reason != null && !reason.isBlank()) ? reason : "Cancelled by the bakery.";
            whatsAppClient.sendTemplate(phone, T_CANCELLED, List.of(firstName(order), ref, clean(templateReason)));
        }
    }

    /** Manual admin message. orderRef may be null (then no template fallback is possible). */
    public void customerUpdate(String phone, String message, String customerName, String orderRef) {
        if (phone == null || phone.isBlank()) return;
        if (whatsAppClient.sendText(phone, message) == SendOutcome.WINDOW_CLOSED) {
            if (orderRef != null) {
                whatsAppClient.sendTemplate(phone, T_UPDATE, List.of(firstName(customerName), orderRef, clean(message)));
            } else {
                log.warn("Manual message to {} not delivered: 24h window closed and no recent order to reference for a template.", phone);
            }
        }
    }

    /** Payment-screenshot reminder (job). Free-form for recent orders; template fallback for advance ones. */
    public void paymentReminder(Order order, String freeFormText) {
        String phone = phone(order);
        if (phone == null) return;
        if (whatsAppClient.sendText(phone, freeFormText) == SendOutcome.WINDOW_CLOSED) {
            whatsAppClient.sendTemplate(phone, T_PAYMENT_REMINDER,
                    List.of(firstName(order), ref(order), deliveryDate(order)));
        }
    }

    // --- helpers ---
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
