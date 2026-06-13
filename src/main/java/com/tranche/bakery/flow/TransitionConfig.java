package com.tranche.bakery.flow;

import lombok.Data;

@Data
public class TransitionConfig {
    private String match;        // specific value, "*" wildcard, or null if matchType used
    private String matchType;    // match on message type: image, text, interactive
    private boolean ignoreCase = true;
    private String nextState;
    private String saveContext;  // key to save input into conversation context
    private String action;       // named action to execute on this transition
    private String errorMessage; // if set: send this message, resend entry, no state change
}
