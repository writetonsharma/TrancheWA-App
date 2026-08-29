package com.tranche.bakery.alert;

import com.tranche.bakery.whatsapp.WhatsAppClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlertService {

    private final AlertRepository alertRepository;
    private final WhatsAppClient whatsAppClient;
    private final TelegramNotifier telegramNotifier;

    @Value("${bakery.admin.phone:}")
    private String adminPhone;

    @Transactional
    public void raise(String type, String message, Long orderId, String customerPhone) {
        Alert alert = new Alert();
        alert.setType(type);
        alert.setMessage(message);
        alert.setOrderId(orderId);
        alert.setCustomerPhone(customerPhone);
        alertRepository.save(alert);
        log.warn("ALERT [{}] order={} customer={} — {}", type, orderId, customerPhone, message);

        String adminText = "⚠️ *Bakery Alert* [" + type + "]\n\n" + message +
                (orderId != null ? "\nOrder ID: " + orderId : "") +
                (customerPhone != null ? "\nCustomer: " + customerPhone : "");

        // Telegram — reliable admin channel, not subject to WhatsApp's 24h window.
        telegramNotifier.send(adminText);

        // Never WhatsApp the admin about a WhatsApp delivery failure: the same channel is down,
        // so this send fails too and each failure spawns another failure alert — a loop that
        // floods the dashboard. Delivery failures are still recorded above for the dashboard.
        boolean isDeliveryFailure = "DELIVERY_FAILURE".equals(type);
        if (adminPhone != null && !adminPhone.isBlank() && !isDeliveryFailure) {
            try {
                whatsAppClient.sendText(adminPhone, adminText);
            } catch (Exception e) {
                log.error("Failed to send admin WhatsApp alert: {}", e.getMessage());
            }
        }
    }

    public void raise(String type, String message) {
        raise(type, message, null, null);
    }

    @Transactional
    public void resolveAll() {
        alertRepository.resolveAll();
    }

    @Transactional
    public void resolve(Long id) {
        alertRepository.resolveById(id);
    }
}
