package com.tranche.bakery;

import com.tranche.bakery.alert.Alert;
import com.tranche.bakery.alert.AlertRepository;
import com.tranche.bakery.feedback.Feedback;
import com.tranche.bakery.feedback.FeedbackRepository;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.whatsapp.CustomerNotifier;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryFeedbackFlowTest extends FlowScenarioBase {

    @Autowired FeedbackRepository feedbackRepository;
    @Autowired AlertRepository alertRepository;
    @Autowired CustomerNotifier customerNotifier;

    @Test
    void deliveredInWindow_sendsFeedbackButtons() {
        Order order = new Order();
        order.setCustomer(customer);
        order.setStatus(OrderStatus.COMPLETED);
        order.setOrderNumber("TRB-TEST-1");
        order = orderRepository.save(order);

        customerNotifier.orderDelivered(order);

        assertThat(sentButtonTitles).contains("Loved it 😍", "Could be better 🙏");
    }

    @Test
    void lovedIt_thenYesReview_sendsReviewLink() {
        send("fbgood");
        assertState("FEEDBACK_REVIEW");
        assertThat(feedbackRepository.findAllByOrderByCreatedAtDesc())
                .extracting(Feedback::getMessage)
                .anyMatch(m -> m.contains("Loved it"));
        assertThat(sentButtonTitles).contains("Yes, sure", "Maybe later");

        send("review_yes");
        assertState("IDLE");
        assertThat(sentTexts).anyMatch(t -> t.contains("g.page"));
    }

    @Test
    void lovedIt_thenMaybeLater_justThanks() {
        send("fbgood");
        send("review_no");
        assertState("IDLE");
        assertThat(sentTexts).anyMatch(t -> t.contains("Thank you for your feedback"));
    }

    @Test
    void couldBeBetter_capturesDetail_andAlertsAdmin() {
        send("fbbad");
        assertState("FEEDBACK_DETAIL");
        assertThat(feedbackRepository.findAllByOrderByCreatedAtDesc())
                .extracting(Feedback::getMessage)
                .anyMatch(m -> m.contains("Could be better"));

        send("The loaf was a little dense this time");
        assertState("IDLE");
        assertThat(feedbackRepository.findAllByOrderByCreatedAtDesc())
                .extracting(Feedback::getMessage)
                .anyMatch(m -> m.contains("Improvement suggestion") && m.contains("dense"));
        assertThat(alertRepository.findAll())
                .extracting(Alert::getType)
                .contains("FEEDBACK");
        assertThat(sentTexts).anyMatch(t -> t.contains("we'll work on this"));
    }

    @Test
    void templateButtonReply_type_button_isProcessed() {
        // A tapped template quick-reply arrives as messageType "button" — it must be supported, not dropped.
        flowEngine.handle(customer, conversation, "button", "fbbad", NullNode.getInstance());
        reloadConversation();
        assertState("FEEDBACK_DETAIL");
    }
}
