package com.tranche.bakery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Any stray message from a resting customer (IDLE / MAIN_MENU) is treated like "hi":
 * they get the greeting and menu instead of having to discover the exact word.
 */
class RestingGreetingFlowTest extends FlowScenarioBase {

    @Test
    void strayTextAtIdle_namedCustomer_showsMenu() {
        send("I want some bread please");
        assertState("MAIN_MENU");
        assertThat(sentButtonBodies).as("main-menu greeting shown").isNotEmpty();
    }

    @Test
    void strayTextAtIdle_namelessCustomer_collectsName() {
        customer.setName(null);
        customer = customerRepository.save(customer);
        send("hello there");
        assertState("NAME_COLLECT");
    }

    @Test
    void strayTextAtMainMenu_reshowsMenu() {
        send("hi");
        assertState("MAIN_MENU");
        sentButtonBodies.clear();
        send("random gibberish");
        assertState("MAIN_MENU");
        assertThat(sentButtonBodies).as("menu reshown after stray text").isNotEmpty();
    }
}
