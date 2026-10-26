package com.tranche.bakery.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.tranche.bakery.conversation.WhatsappConversation;
import com.tranche.bakery.customer.Customer;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.function.Consumer;

@Getter
@Builder
public class ActionContext {
    private final Customer customer;
    private final WhatsappConversation conversation;
    private final String input;
    private final String messageType;
    private final JsonNode rawMessage;
    /** Flow-owned queue, drained only after a successful commit AND transaction cleanup. */
    private final Consumer<Runnable> postCommit;

    public void afterCommit(Runnable effect) {
        if (postCommit == null) {
            throw new IllegalStateException("Post-commit dispatch requires a FlowEngine action context");
        }
        postCommit.accept(effect);
    }

    /** Set by an action to override the next state the engine transitions to. */
    @Setter
    private String redirectState;

    public Map<String, Object> context() {
        return conversation.getContext();
    }

    public String contextValue(String key) {
        if (conversation.getContext() == null) return null;
        Object val = conversation.getContext().get(key);
        return val == null ? null : val.toString();
    }
}
