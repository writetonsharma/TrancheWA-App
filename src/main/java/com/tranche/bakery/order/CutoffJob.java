package com.tranche.bakery.order;

import com.tranche.bakery.conversation.ConversationRepository;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

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

    @Scheduled(cron = "0 0 18 * * *")
    @Transactional
    public void cancelUnfinishedOrders() {
        List<Order> expiredOrders = orderRepository.findAllByStatusIn(
                Set.of(OrderStatus.DRAFT, OrderStatus.PENDING_CONFIRMATION)
        );

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
