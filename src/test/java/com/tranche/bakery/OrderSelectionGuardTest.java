package com.tranche.bakery;

import org.junit.jupiter.api.Test;

import com.tranche.bakery.menu.MenuCategory;
import com.tranche.bakery.menu.MenuItem;

import java.util.List;

/** The category/item steps must ignore stale taps (a previous list row) or typed text
 *  instead of advancing with a bogus id. Reproduces the "tapped Loaves again → added Country Loaf" bug. */
class OrderSelectionGuardTest extends FlowScenarioBase {

    private void reachItemStep() {
        send("hi");
        send("order");
        send(nextDeliveryDate());
        assertState("ORDER_SELECT_CATEGORY");
        send(firstCategoryId());
        assertState("ORDER_SELECT_ITEM");
    }

    @Test
    void itemFromAnotherCategoryAtItemStep_reprompts() {
        List<MenuCategory> cats = categoryRepository.findAllByActiveTrueOrderByDisplayOrderAsc();
        MenuItem otherItem = itemRepository
                .findAllByCategoryAndActiveTrueOrderByDisplayOrderAsc(cats.get(1)).get(0);
        reachItemStep(); // category = cats.get(0)
        send(otherItem.getId().toString()); // item belongs to a different category
        assertState("ORDER_SELECT_ITEM");
    }

    @Test
    void typedTextAtItemStep_reprompts() {
        reachItemStep();
        send("country loaf");
        assertState("ORDER_SELECT_ITEM");
    }

    @Test
    void validItemAtItemStep_advancesToQuantity() {
        String catId = firstCategoryId();
        send("hi");
        send("order");
        send(nextDeliveryDate());
        send(catId);
        send(firstItemId(catId));
        assertState("ORDER_SELECT_QUANTITY");
    }

    @Test
    void invalidCategoryAtCategoryStep_reprompts() {
        send("hi");
        send("order");
        send(nextDeliveryDate());
        assertState("ORDER_SELECT_CATEGORY");
        send("999999"); // no such category
        assertState("ORDER_SELECT_CATEGORY");
    }
}
