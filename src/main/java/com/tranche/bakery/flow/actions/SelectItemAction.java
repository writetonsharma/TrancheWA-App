package com.tranche.bakery.flow.actions;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.menu.MenuItemRepository;
import com.tranche.bakery.order.DeliveryRules;
import com.tranche.bakery.whatsapp.WhatsAppClient;

import lombok.RequiredArgsConstructor;

/** Guards the item step: only a real, active item of the chosen category (deliverable on the date)
 *  advances; a stale/previous tap or typed text re-prompts the item list instead. */
@Component
@RequiredArgsConstructor
public class SelectItemAction implements FlowAction {

    private final MenuItemRepository itemRepository;
    private final DeliveryRules deliveryRules;
    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "SELECT_ITEM"; }

    @Override
    public void execute(ActionContext ctx) {
        if (isValidSelection(ctx)) return; // valid → proceed to ORDER_SELECT_QUANTITY

        whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                "Please tap an item from the list below to continue. 🥖");
        ctx.setRedirectState("ORDER_SELECT_ITEM");
    }

    private boolean isValidSelection(ActionContext ctx) {
        Long itemId = parseLong(ctx.getInput());
        Long categoryId = parseLong(ctx.contextValue("categoryId"));
        if (itemId == null || categoryId == null) return false;

        MenuItem item = itemRepository.findById(itemId).orElse(null);
        if (item == null || !item.isActive()) return false;
        if (item.getCategory() == null || !categoryId.equals(item.getCategory().getId())) return false;

        LocalDate day = parseDate(ctx.contextValue("deliveryDate"));
        boolean fnf = ctx.getCustomer() != null && ctx.getCustomer().isFriendsAndFamily();
        return day == null || deliveryRules.itemDeliverableOn(item.getName(), day, fnf);
    }

    private static Long parseLong(String s) {
        if (s == null) return null;
        try { return Long.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static LocalDate parseDate(String s) {
        if (s == null) return null;
        try { return LocalDate.parse(s.trim()); } catch (Exception e) { return null; }
    }
}
