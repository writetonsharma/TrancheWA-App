package com.tranche.bakery.customer;

import com.tranche.bakery.admin.AdminMessage;
import com.tranche.bakery.admin.AdminMessageRepository;
import com.tranche.bakery.conversation.ConversationRepository;
import com.tranche.bakery.conversation.WhatsappConversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class GhostCustomerCleanupJobTest {

    @Autowired GhostCustomerCleanupJob job;
    @Autowired CustomerRepository customerRepository;
    @Autowired ConversationRepository conversationRepository;
    @Autowired AdminMessageRepository adminMessageRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final LocalDateTime RECENT = LocalDateTime.now().withNano(0);
    private static final LocalDateTime OLD = RECENT.minusDays(10);

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE TABLE admin_messages, payment_screenshots, payments, order_items, orders, " +
                "whatsapp_conversations, feedback, alerts, customers RESTART IDENTITY CASCADE");
    }

    private Customer save(String phone, String name, LocalDateTime created, LocalDateTime inbound) {
        Customer c = new Customer();
        c.setPhone(phone);
        c.setName(name);
        c.setCreatedAt(created);      // updatable=false blocks UPDATE, not INSERT — backdates the row
        c.setLastInboundAt(inbound);
        return customerRepository.save(c);
    }

    private void giveConversation(Customer c) {
        WhatsappConversation conv = new WhatsappConversation();
        conv.setCustomer(c);
        conv.setState("MAIN_MENU");
        conv.setContext(new HashMap<>());
        conversationRepository.save(conv);
    }

    @Test
    void preserves_active_web_only_customer_without_changing_inbound_or_marketing() {
        Customer customer = save("910000000007", null, OLD, null);
        customer.setMarketingOptIn(false);
        customerRepository.saveAndFlush(customer);
        jdbcTemplate.update("UPDATE customers SET last_web_activity_at = ? WHERE phone = ?", RECENT, customer.getPhone());

        job.purgeGhostCustomers();

        Customer kept = customerRepository.findByPhone(customer.getPhone()).orElseThrow();
        assertThat(kept.getLastWebActivityAt()).isEqualTo(RECENT);
        assertThat(kept.getLastInboundAt()).isNull();
        assertThat(kept.isMarketingOptIn()).isFalse();
    }

    @Test
    void purges_old_inactive_web_ghost_and_legacy_ghost_without_web_activity() {
        Customer webGhost = save("910000000008", null, OLD, null);
        jdbcTemplate.update("UPDATE customers SET last_web_activity_at = ? WHERE phone = ?", OLD, webGhost.getPhone());
        Customer legacyGhost = save("910000000009", null, OLD, null);

        job.purgeGhostCustomers();

        assertThat(customerRepository.findByPhone(webGhost.getPhone())).isEmpty();
        assertThat(customerRepository.findByPhone(legacyGhost.getPhone())).isEmpty();
    }

    @Test
    void purges_only_stale_nameless_customers_with_no_progress() {
        Customer ghost = save("910000000001", null, OLD, OLD);       // stray spam number
        giveConversation(ghost);

        save("910000000002", "Asha", OLD, OLD);                       // has a name -> real lead
        save("910000000003", null, RECENT, RECENT);                   // too new
        save("910000000004", null, OLD, RECENT);                      // messaged us recently (active)

        Customer fnf = save("910000000005", null, OLD, OLD);          // admin-added F&F
        fnf.setSubscriptionEligible(true);
        customerRepository.save(fnf);

        Customer messaged = save("910000000006", null, OLD, OLD);     // admin messaged them
        AdminMessage m = new AdminMessage();
        m.setCustomer(messaged);
        m.setDirection(AdminMessage.Direction.OUTBOUND);
        m.setMessage("hello");
        adminMessageRepository.save(m);

        job.purgeGhostCustomers();

        assertThat(customerRepository.findByPhone("910000000001")).isEmpty();                              // ghost purged
        assertThat(conversationRepository.findTopByCustomerOrderByStartedAtDesc(ghost)).isEmpty();         // its conversation too
        assertThat(customerRepository.findByPhone("910000000002")).isPresent();                            // named kept
        assertThat(customerRepository.findByPhone("910000000003")).isPresent();                            // recent kept
        assertThat(customerRepository.findByPhone("910000000004")).isPresent();                            // active kept
        assertThat(customerRepository.findByPhone("910000000005")).isPresent();                            // F&F kept
        assertThat(customerRepository.findByPhone("910000000006")).isPresent();                            // messaged kept
    }
}
