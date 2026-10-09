package com.tranche.bakery.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.node.NullNode;
import com.tranche.bakery.FlowScenarioBase;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.whatsapp.SendOutcome;

class MarketingTest extends FlowScenarioBase {

    @Autowired
    MarketingService marketingService;

    private void orderFor(Customer c) {
        Order o = new Order();
        o.setCustomer(c);
        o.setStatus(OrderStatus.CONFIRMED);
        orderRepository.save(o);
    }

    @Test
    void broadcast_sendsToOptedInOnly_withFirstNameAndButtons() {
        when(whatsAppClient.sendMarketingTemplate(anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(SendOutcome.SENT);

        orderFor(customer); // base customer: opted in, name "Test User"

        Customer optedOut = new Customer();
        optedOut.setPhone("919000000002");
        optedOut.setName("Ravi Kumar");
        optedOut.setMarketingOptIn(false);
        optedOut = customerRepository.save(optedOut);
        orderFor(optedOut);

        assertThat(marketingService.audience()).extracting(Customer::getPhone)
                .contains("919000000001", "919000000002");

        var result = marketingService.broadcast(List.of(customer.getId(), optedOut.getId()), "https://img/bread.jpg");

        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        verify(whatsAppClient).sendMarketingTemplate("919000000001", "new_millet_loaf", "https://img/bread.jpg",
                List.of("Test"), List.of("order_now", "stop_promotions"));
        verify(whatsAppClient, never())
                .sendMarketingTemplate(eq("919000000002"), any(), any(), anyList(), anyList());
    }

    @Test
    void stopPromotions_button_optsOut() {
        assertThat(customer.isMarketingOptIn()).isTrue();
        flowEngine.handle(customer, conversation, "button", "stop_promotions", NullNode.getInstance());
        assertThat(customerRepository.findById(customer.getId()).orElseThrow().isMarketingOptIn()).isFalse();
    }

    @Test
    void typedStop_optsOut() {
        send("stop");
        assertThat(customerRepository.findById(customer.getId()).orElseThrow().isMarketingOptIn()).isFalse();
    }

    @Test
    void orderNow_button_startsOrderFlow() {
        flowEngine.handle(customer, conversation, "button", "order_now", NullNode.getInstance());
        reloadConversation();
        assertState("ORDER_SELECT_CATEGORY");
    }
}
