package com.tranche.bakery.marketing;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tranche.bakery.admin.AdminMessage;
import com.tranche.bakery.admin.AdminMessageRepository;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.customer.CustomerRepository;
import com.tranche.bakery.whatsapp.SendOutcome;
import com.tranche.bakery.whatsapp.WhatsAppClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Promotional WhatsApp broadcasts. Sends an approved MARKETING template (image header +
 * first-name body + "Order now" / "Stop promotions" quick-reply buttons) to selected
 * customers who have opted in. Opting out (STOP / the button) is handled in FlowEngine.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketingService {

    private final CustomerRepository customerRepository;
    private final WhatsAppClient whatsAppClient;
    private final AdminMessageRepository adminMessageRepository;

    @Value("${bakery.marketing.template-name:new_millet_loaf}")
    private String templateName;

    @Value("${bakery.marketing.image-url:}")
    private String defaultImageUrl;

    // Quick-reply payloads returned in the webhook when the customer taps a button.
    private static final List<String> BUTTON_PAYLOADS = List.of("order_now", "stop_promotions");

    public List<Customer> audience() {
        return customerRepository.findMarketingAudience();
    }

    public String templateName() {
        return templateName;
    }

    public String defaultImageUrl() {
        return defaultImageUrl;
    }

    /** Send the marketing template to the chosen customers; opted-out ones are skipped. */
    @Transactional
    public BroadcastResult broadcast(List<Long> customerIds, String imageUrlOverride) {
        String imageUrl = (imageUrlOverride != null && !imageUrlOverride.isBlank())
                ? imageUrlOverride.trim() : defaultImageUrl;
        int sent = 0, skipped = 0, failed = 0;
        for (Long id : customerIds) {
            Customer c = customerRepository.findById(id).orElse(null);
            if (c == null || !c.isMarketingOptIn()) { skipped++; continue; }
            SendOutcome outcome = whatsAppClient.sendMarketingTemplate(
                    c.getPhone(), templateName, imageUrl, List.of(firstName(c)), BUTTON_PAYLOADS);
            if (outcome == SendOutcome.SENT) {
                sent++;
                logOutbound(c, "[promotion] " + templateName);
            } else {
                failed++;
                log.warn("Marketing send to {} outcome={}", c.getPhone(), outcome);
            }
        }
        log.info("Marketing broadcast '{}' -> sent={} skipped={} failed={}", templateName, sent, skipped, failed);
        return new BroadcastResult(sent, skipped, failed);
    }

    /** Flip a customer out of promotional messages (STOP reply or the "Stop promotions" button). */
    @Transactional
    public void optOut(Customer customer) {
        if (customer.isMarketingOptIn()) {
            customer.setMarketingOptIn(false);
            customerRepository.save(customer);
        }
    }

    private void logOutbound(Customer c, String text) {
        AdminMessage m = new AdminMessage();
        m.setCustomer(c);
        m.setDirection(AdminMessage.Direction.OUTBOUND);
        m.setMessage(text);
        adminMessageRepository.save(m);
    }

    private String firstName(Customer c) {
        if (c.getName() == null || c.getName().isBlank()) return "there";
        String first = c.getName().trim().split("\\s+")[0];
        return first.isBlank() ? "there" : first;
    }

    public record BroadcastResult(int sent, int skipped, int failed) {}
}
