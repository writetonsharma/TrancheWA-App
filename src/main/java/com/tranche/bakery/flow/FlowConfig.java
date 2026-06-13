package com.tranche.bakery.flow;

import lombok.Data;
import java.util.Map;

@Data
public class FlowConfig {
    private String initialState;
    private Map<String, StateConfig> states;
}
