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

        if (adminPhone != null && !adminPhone.isBlank()) {
            try {
                whatsAppClient.sendText(adminPhone,
                        "⚠️ *Bakery Alert* [" + type + "]\n\n" + message +
                        (orderId != null ? "\nOrder ID: " + orderId : "") +
                        (customerPhone != null ? "\nCustomer: " + customerPhone : ""));
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
}
