package com.tranche.bakery.customer;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.tranche.bakery.conversation.ConversationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Weekly housekeeping that removes "ghost" customer rows — stray or spam numbers that message the
 * bot once (creating a customer via the webhook) and never progress. A row is deleted only when it
 * has no name, no pricing/F&F flag, no orders, subscriptions, admin messages or feedback, and has
 * been dormant since before the cutoff. Disable with bakery.cleanup.enabled=false.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GhostCustomerCleanupJob {

    private final CustomerRepository customerRepository;
    private final ConversationRepository conversationRepository;

    @Value("${bakery.cleanup.enabled:true}")
    private boolean enabled;

    @Value("${bakery.cleanup.stale-days:7}")
    private int staleDays;

    @Scheduled(cron = "0 15 4 * * SUN", zone = "Asia/Kolkata")
    @Transactional
    public void purgeGhostCustomers() {
        if (!enabled) return;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(staleDays);
        List<Customer> ghosts = customerRepository.findStaleGhosts(cutoff);
        if (ghosts.isEmpty()) return;

        conversationRepository.deleteByCustomerIn(ghosts);   // clear the FK child first
        customerRepository.deleteAll(ghosts);
        log.info("Ghost-customer cleanup removed {} stale number(s): {}", ghosts.size(),
                ghosts.stream().map(Customer::getPhone).toList());
    }
}
