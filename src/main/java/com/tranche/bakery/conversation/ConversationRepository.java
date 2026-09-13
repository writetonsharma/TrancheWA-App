package com.tranche.bakery.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import com.tranche.bakery.customer.Customer;
import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<WhatsappConversation, Long> {
    Optional<WhatsappConversation> findTopByCustomerOrderByStartedAtDesc(Customer customer);

    @Modifying
    @Query("DELETE FROM WhatsappConversation w WHERE w.customer IN :customers")
    void deleteByCustomerIn(List<Customer> customers);
}
