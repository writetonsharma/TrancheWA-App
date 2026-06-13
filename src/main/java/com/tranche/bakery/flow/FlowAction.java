package com.tranche.bakery.flow;

public interface FlowAction {
    String getName();
    void execute(ActionContext ctx);
}
