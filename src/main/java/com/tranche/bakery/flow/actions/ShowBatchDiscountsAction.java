package com.tranche.bakery.flow.actions;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.tranche.bakery.conversation.ConversationRepository;
import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.offer.BatchDiscountService;
import com.tranche.bakery.offer.BatchDiscountService.Nudge;
import com.tranche.bakery.order.DeliveryRules;
import com.tranche.bakery.whatsapp.WhatsAppClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Greeting nudge: when a customer reaches the main menu, surface any items that are
 * currently on a live batch discount over the next few bake days, so they can be
 * nudged into joining an existing batch. Throttled to once per customer per day so
 * repeated main-menu visits are not spammed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ShowBatchDiscountsAction implements FlowAction {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("EEE");
    private static final int LOOKAHEAD_DAYS = 3;
    private static final String THROTTLE_KEY = "batchNudgeDate";

    private final BatchDiscountService batchDiscountService;
    private final DeliveryRules deliveryRules;
    private final WhatsAppClient whatsAppClient;
    private final ConversationRepository conversationRepository;

    @Override
    public String getName() { return "SHOW_BATCH_DISCOUNTS"; }

    @Override
    public void execute(ActionContext ctx) {
        if (!batchDiscountService.isEnabled()) return;

        Map<String, Object> context = ctx.getConversation().getContext();
        String today = LocalDate.now().toString();
        if (context != null && today.equals(context.get(THROTTLE_KEY))) return;

        List<LocalDate> dates = deliveryRules.upcomingDeliverableDays(LOOKAHEAD_DAYS);
        List<Nudge> nudges = batchDiscountService.liveNudges(dates);
        if (nudges.isEmpty()) return;

        whatsAppClient.sendText(ctx.getCustomer().getPhone(), buildMessage(nudges));

        Map<String, Object> newCtx = context != null ? new HashMap<>(context) : new HashMap<>();
        newCtx.put(THROTTLE_KEY, today);
        ctx.getConversation().setContext(newCtx);
        conversationRepository.save(ctx.getConversation());
        log.info("Showed {} batch-discount nudge(s) to {}", nudges.size(), ctx.getCustomer().getPhone());
    }

    private String buildMessage(List<Nudge> nudges) {
        StringBuilder sb = new StringBuilder(
                "\uD83D\uDD25 *Live batch discounts* \u2014 join a batch and save extra:\n\n");
        for (Nudge n : nudges) {
            String days = n.dates().stream().map(d -> d.format(DAY_FMT)).collect(Collectors.joining(", "));
            sb.append(String.format("\u2022 %s \u2014 *%s%% off* (%s)\n", n.itemName(), pct(n.percent()), days));
        }
        sb.append("\n_Stacked on top of your usual discount, when you order for those days._");
        return sb.toString();
    }

    private String pct(java.math.BigDecimal p) {
        return p.stripTrailingZeros().toPlainString();
    }
}
