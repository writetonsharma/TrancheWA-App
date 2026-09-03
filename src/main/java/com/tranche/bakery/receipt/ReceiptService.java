package com.tranche.bakery.receipt;

import com.tranche.bakery.order.Order;
import com.tranche.bakery.subscription.Subscription;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the customer receipt: render PDF, upload to WhatsApp, send as a
 * downloadable document. Best-effort by design ? any failure is logged and
 * swallowed so it can never break order confirmation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReceiptService {

    private final ReceiptProperties props;
    private final ReceiptPdfService pdfService;
    private final WhatsAppClient whatsAppClient;
    private final com.tranche.bakery.order.OrderNumberGenerator orderNumberGenerator;

    public record ReceiptMedia(String mediaId, String filename) {}

    /** Renders the receipt PDF and uploads it to WhatsApp, returning the media handle (or null if disabled/unavailable). */
    public ReceiptMedia prepare(Order order) {
        if (!props.isEnabled()) return null;
        if (order == null || order.getCustomer() == null) return null;

        String phone = order.getCustomer().getPhone();
        if (phone == null || phone.isBlank()) return null;

        String receiptNo = order.getOrderNumber() != null ? order.getOrderNumber() : "" + order.getId();
        try {
            byte[] pdf = pdfService.build(order);
            String filename = "Tranche-Receipt-" + receiptNo.replace("#", "") + ".pdf";
            String mediaId = whatsAppClient.uploadMedia(pdf, filename, "application/pdf");
            if (mediaId == null || mediaId.isBlank()) return null;
            return new ReceiptMedia(mediaId, filename);
        } catch (Exception e) {
            log.warn("Could not prepare receipt for order {}: {}", receiptNo, e.getMessage());
            return null;
        }
    }

    /** Renders the prepaid-subscription receipt PDF and uploads it, returning the media handle (or null if disabled/unavailable). */
    public ReceiptMedia prepareSubscription(Subscription sub) {
        if (!props.isEnabled()) return null;
        if (sub == null || sub.getCustomer() == null) return null;

        String phone = sub.getCustomer().getPhone();
        if (phone == null || phone.isBlank()) return null;

        try {
            String receiptNo = subscriptionReceiptNo(sub);
            byte[] pdf = pdfService.build(sub, receiptNo);
            String filename = "Tranche-Receipt-" + receiptNo + ".pdf";
            String mediaId = whatsAppClient.uploadMedia(pdf, filename, "application/pdf");
            if (mediaId == null || mediaId.isBlank()) return null;
            return new ReceiptMedia(mediaId, filename);
        } catch (Exception e) {
            log.warn("Could not prepare subscription receipt for {}: {}", sub.getId(), e.getMessage());
            return null;
        }
    }

    // Subscription receipt number in the same TRB style as orders, marked SUB and non-sequential.
    private String subscriptionReceiptNo(Subscription sub) {
        java.time.LocalDateTime created = sub.getCreatedAt() != null ? sub.getCreatedAt() : java.time.LocalDateTime.now();
        return orderNumberGenerator.generate(sub.getId(), created).replaceFirst("TRB-", "TRB-SUB-");
    }
}
