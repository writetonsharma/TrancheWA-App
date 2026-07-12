package com.tranche.bakery.order;

import com.tranche.bakery.conversation.ConversationRepository;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class CutoffJob {

    private final OrderRepository orderRepository;
    private final ConversationRepository conversationRepository;
    private final WhatsAppClient whatsAppClient;

    private static final String CUTOFF_MESSAGE =
            "⏰ A gentle note — it's 6 PM and your order is still incomplete, so it has been set aside for today.\n\n" +
            "Whenever you're ready, simply send *hi* to start fresh. We'd love to bake for you! 🥖";

    @Scheduled(cron = "0 0 18 * * *", zone = "Asia/Kolkata")
    @Transactional
    public void cancelUnfinishedOrders() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        // All unconfirmed drafts — always cancel at cutoff
        List<Order> drafts = orderRepository.findAllByStatusIn(List.of(OrderStatus.DRAFT));

        // Confirmed but unpaid — only cancel if delivery date is tomorrow or earlier (cutoff passed)
        List<Order> pendingPayment = orderRepository.findAllByStatusIn(
                List.of(OrderStatus.PENDING_CONFIRMATION)).stream()
                .filter(o -> o.getDeliveryDate() == null || !o.getDeliveryDate().isAfter(tomorrow))
                .toList();

        List<Order> expiredOrders = new ArrayList<>();
        expiredOrders.addAll(drafts);
        expiredOrders.addAll(pendingPayment);

        // Mark confirmed-but-unpaid cancellations so a late payment against the old QR can revive them.
        pendingPayment.forEach(o -> o.setCutoffCancelled(true));

        if (expiredOrders.isEmpty()) {
            log.info("Cutoff job: no unfinished orders to cancel.");
            return;
        }

        log.info("Cutoff job: cancelling {} unfinished orders.", expiredOrders.size());

        for (Order order : expiredOrders) {
            order.setStatus(OrderStatus.CANCELLED);
            orderRepository.save(order);

            conversationRepository
                    .findTopByCustomerOrderByStartedAtDesc(order.getCustomer())
                    .ifPresent(conv -> {
                        conv.setState("IDLE");
                        conv.setContext(null);
                        conversationRepository.save(conv);
                    });

            try {
                whatsAppClient.sendText(order.getCustomer().getPhone(), CUTOFF_MESSAGE);
            } catch (Exception e) {
                log.warn("Cutoff job: failed to notify customer {} — {}",
                        order.getCustomer().getPhone(), e.getMessage());
            }
        }
    }
}
