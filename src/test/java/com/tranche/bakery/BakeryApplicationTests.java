package com.tranche.bakery;

import org.junit.jupiter.api.Test;

public class BakeryApplicationTests extends FlowScenarioBase {

    @Test
    public void contextLoads() {
        // Verifies that the full Spring context (Flyway, JPA, menu sync) starts cleanly
        // against the TestContainers Postgres — inherited from FlowScenarioBase.
    }
}
