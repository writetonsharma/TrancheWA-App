package com.tranche.bakery.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import com.tranche.bakery.customer.Customer;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<WhatsappConversation, Long> {
    Optional<WhatsappConversation> findTopByCustomerOrderByStartedAtDesc(Customer customer);
}
