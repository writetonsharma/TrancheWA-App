package com.tranche.bakery.receipt;

import com.tranche.bakery.order.Order;
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

    public void sendReceipt(Order order) {
        if (!props.isEnabled()) return;
        if (order == null || order.getCustomer() == null) return;

        String phone = order.getCustomer().getPhone();
        if (phone == null || phone.isBlank()) return;

        String receiptNo = order.getOrderNumber() != null ? order.getOrderNumber() : "" + order.getId();
        try {
            byte[] pdf = pdfService.build(order);
            String filename = "Tranche-Receipt-" + receiptNo.replace("#", "") + ".pdf";
            String mediaId = whatsAppClient.uploadMedia(pdf, filename, "application/pdf");
            whatsAppClient.sendDocument(phone, mediaId, filename,
                    "Here is your receipt. Thank you for ordering from " + props.getBusinessName() + ".");
            log.info("Receipt sent for order {} to {}", receiptNo, phone);
        } catch (Exception e) {
            log.warn("Could not send receipt for order {}: {}", receiptNo, e.getMessage());
        }
    }
}
