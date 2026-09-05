package com.tranche.bakery.flow.actions;

import org.springframework.stereotype.Component;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.menu.MenuCategoryRepository;
import com.tranche.bakery.whatsapp.WhatsAppClient;

import lombok.RequiredArgsConstructor;

/** Guards the category step: only a real, active category advances; a stale/previous tap or typed
 *  text re-prompts the category list instead. */
@Component
@RequiredArgsConstructor
public class SelectCategoryAction implements FlowAction {

    private final MenuCategoryRepository categoryRepository;
    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "SELECT_CATEGORY"; }

    @Override
    public void execute(ActionContext ctx) {
        if (isValidCategory(ctx.getInput())) return; // valid → proceed to ORDER_SELECT_ITEM

        whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                "Please choose a category from the list below. 🥖");
        ctx.setRedirectState("ORDER_SELECT_CATEGORY");
    }

    private boolean isValidCategory(String input) {
        if (input == null) return false;
        Long id;
        try { id = Long.valueOf(input.trim()); } catch (NumberFormatException e) { return false; }
        return categoryRepository.findById(id).map(c -> c.isActive()).orElse(false);
    }
}
