package com.tranche.bakery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tranche.bakery.conversation.ConversationRepository;
import com.tranche.bakery.conversation.WhatsappConversation;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.customer.CustomerRepository;
import com.tranche.bakery.flow.FlowEngine;
import com.tranche.bakery.menu.MenuCategoryRepository;
import com.tranche.bakery.menu.MenuItemRepository;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@ActiveProfiles("test")
public abstract class FlowScenarioBase {

    @Autowired protected FlowEngine flowEngine;
    @Autowired protected CustomerRepository customerRepository;
    @Autowired protected ConversationRepository conversationRepository;
    @Autowired protected OrderRepository orderRepository;
    @Autowired protected MenuCategoryRepository categoryRepository;
    @Autowired protected MenuItemRepository itemRepository;
    @Autowired protected JdbcTemplate jdbcTemplate;

    @MockBean protected WhatsAppClient whatsAppClient;

    protected Customer customer;
    protected WhatsappConversation conversation;

    protected final List<String> sentTexts = new ArrayList<>();
    protected final List<String> sentButtonBodies = new ArrayList<>();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUpBase() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE admin_messages, payment_screenshots, payments, order_items, orders, " +
                "whatsapp_conversations, feedback, alerts, customers RESTART IDENTITY CASCADE");

        customer = new Customer();
        customer.setPhone("919000000001");
        customer.setName("Test User");
        customer.setDeliveryArea("122001");
        customer.setDeliveryAddress("Flat 1, Test Tower, Sector 50");
        customer.setLocationLat(new BigDecimal("28.456789"));
        customer.setLocationLng(new BigDecimal("77.123456"));
        customer = customerRepository.save(customer);

        conversation = new WhatsappConversation();
        conversation.setCustomer(customer);
        conversation.setState("IDLE");
        conversation.setContext(new HashMap<>());
        conversation = conversationRepository.save(conversation);

        sentTexts.clear();
        sentButtonBodies.clear();

        doAnswer(inv -> { sentTexts.add(inv.getArgument(1, String.class)); return null; })
                .when(whatsAppClient).sendText(any(), any());
        doAnswer(inv -> { sentButtonBodies.add(inv.getArgument(1, String.class)); return null; })
                .when(whatsAppClient).sendButtons(any(), any(), any());
    }

    protected void send(String input) {
        flowEngine.handle(customer, conversation, "text", input, NullNode.getInstance());
        reloadConversation();
    }

    protected void sendImage(String mediaId) {
        ObjectNode node = MAPPER.createObjectNode();
        node.putObject("image").put("id", mediaId);
        flowEngine.handle(customer, conversation, "image", "", node);
        reloadConversation();
    }

    protected void reloadConversation() {
        conversation = conversationRepository.findById(conversation.getId()).orElseThrow();
    }

    protected void assertState(String expected) {
        assertThat(conversation.getState()).as("conversation state").isEqualTo(expected);
    }

    protected void assertOrderStatus(Long orderId, OrderStatus expected) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).as("order %d status", orderId).isEqualTo(expected);
    }

    protected String firstCategoryId() {
        return categoryRepository.findAllByActiveTrueOrderByDisplayOrderAsc()
                .get(0).getId().toString();
    }

    protected String firstItemId(String categoryId) {
        var category = categoryRepository.findById(Long.parseLong(categoryId)).orElseThrow();
        return itemRepository.findAllByCategoryAndActiveTrueOrderByDisplayOrderAsc(category)
                .get(0).getId().toString();
    }

    protected String nextDeliveryDate() {
        LocalDate start = LocalDate.now().plusDays(
                LocalTime.now().getHour() >= 23 ? 2 : 1);
        while (start.getDayOfWeek() == DayOfWeek.MONDAY) start = start.plusDays(1);
        return start.toString();
    }

    protected String secondDeliveryDate() {
        LocalDate d1 = LocalDate.parse(nextDeliveryDate());
        LocalDate d2 = d1.plusDays(1);
        if (d2.getDayOfWeek() == DayOfWeek.MONDAY) d2 = d2.plusDays(1);
        return d2.toString();
    }

    protected Long driveToPaymentQr(String deliveryDate) {
        String catId  = firstCategoryId();
        String itemId = firstItemId(catId);

        send("hi");
        send("order");
        send(catId);
        send(itemId);
        send("1");
        send("view_order");
        send(deliveryDate);
        send("use_address");
        send("pref_gate");
        send("confirm");

        assertState("PAYMENT_PENDING");
        List<Order> pending = orderRepository.findAllByCustomerIdAndStatus(
                customer.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(pending).as("one PENDING_CONFIRMATION order after confirm").hasSize(1);
        return pending.get(0).getId();
    }

    protected Long driveToPaymentQr() {
        return driveToPaymentQr(nextDeliveryDate());
    }
}
