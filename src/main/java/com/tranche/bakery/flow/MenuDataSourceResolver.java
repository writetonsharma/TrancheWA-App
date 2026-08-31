package com.tranche.bakery.flow;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.delivery.DeliveryAreaLoader;
import com.tranche.bakery.menu.MenuCategory;
import com.tranche.bakery.menu.MenuCategoryRepository;
import com.tranche.bakery.menu.MenuItemRepository;
import com.tranche.bakery.order.DeliveryRules;
import com.tranche.bakery.subscription.SubscriptionCatalog;
import com.tranche.bakery.whatsapp.WhatsAppMessage;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MenuDataSourceResolver implements DataSourceResolver {

    private final MenuCategoryRepository categoryRepository;
    private final MenuItemRepository itemRepository;
    private final DeliveryAreaLoader deliveryAreaLoader;
    private final DeliveryRules deliveryRules;
    private final SubscriptionCatalog subscriptionCatalog;

    @Override
    public List<WhatsAppMessage.Section> resolve(String dataSource, Map<String, Object> context, Customer customer) {
        boolean fnf = customer != null && customer.isFriendsAndFamily();
        return switch (dataSource) {
            case "MENU_CATEGORIES" -> resolveCategories(context, fnf);
            case "MENU_ITEMS"      -> resolveItems(context, fnf);
            case "DELIVERY_AREAS"  -> resolveDeliveryAreas();
            case "DELIVERY_DATES"  -> resolveDeliveryDates(context, fnf);
            case "SUBSCRIPTION_PLANS"   -> resolveSubscriptionPlans();
            case "SUBSCRIPTION_OPTIONS" -> resolveSubscriptionOptions(context);
            case "SUBSCRIPTION_ITEMS"   -> resolveSubscriptionItems(context);
            case "SUBSCRIPTION_DAYS"    -> resolveSubscriptionDays();
            default -> throw new IllegalArgumentException("Unknown dataSource: " + dataSource);
        };
    }

    private List<WhatsAppMessage.Section> resolveCategories(Map<String, Object> context, boolean fnf) {
        final LocalDate day = parseDeliveryDate(context);
        List<WhatsAppMessage.Row> rows = categoryRepository
                .findAllByActiveTrueOrderByDisplayOrderAsc()
                .stream()
                .filter(c -> day == null || categoryHasItemsForDate(c, day, fnf))
                .map(c -> new WhatsAppMessage.Row(c.getId().toString(), c.getName()))
                .toList();
        return List.of(new WhatsAppMessage.Section("Categories", rows));
    }

    /** True if the category has at least one active item deliverable on the date. */
    private boolean categoryHasItemsForDate(com.tranche.bakery.menu.MenuCategory category, LocalDate day, boolean fnf) {
        return itemRepository.findAllByCategoryAndActiveTrueOrderByDisplayOrderAsc(category).stream()
                .anyMatch(i -> deliveryRules.itemDeliverableOn(i.getName(), day, fnf));
    }

    /** Reads a "deliveryDate" (ISO yyyy-MM-dd) from context, or null if absent/invalid. */
    private LocalDate parseDeliveryDate(Map<String, Object> context) {
        Object dateVal = context != null ? context.get("deliveryDate") : null;
        if (dateVal == null) return null;
        try { return LocalDate.parse(dateVal.toString()); }
        catch (Exception ignored) { return null; }
    }

    private List<WhatsAppMessage.Section> resolveItems(Map<String, Object> context, boolean fnf) {
        Object categoryIdVal = context != null ? context.get("categoryId") : null;
        if (categoryIdVal == null) return List.of();

        Long categoryId = Long.parseLong(categoryIdVal.toString());
        MenuCategory category = categoryRepository.findById(categoryId).orElse(null);
        if (category == null) return List.of();

        final LocalDate day = parseDeliveryDate(context);

        List<WhatsAppMessage.Row> rows = itemRepository
                .findAllByCategoryAndActiveTrueOrderByDisplayOrderAsc(category)
                .stream()
                .filter(i -> day == null || deliveryRules.itemDeliverableOn(i.getName(), day, fnf))
                .map(i -> {
                    String price = String.format("₹%.0f", i.getPrice());
                    String desc = (i.getDescription() != null && !i.getDescription().isBlank())
                            ? price + " · " + i.getDescription()
                            : price;
                    String title = (i.getListTitle() != null && !i.getListTitle().isBlank())
                            ? i.getListTitle()
                            : i.getName();
                    return new WhatsAppMessage.Row(i.getId().toString(), title, desc);
                })
                .toList();
        return List.of(new WhatsAppMessage.Section(category.getName(), rows));
    }

    private List<WhatsAppMessage.Section> resolveDeliveryAreas() {
        List<WhatsAppMessage.Row> rows = deliveryAreaLoader.getAreas().stream()
                .map(a -> new WhatsAppMessage.Row(a.id(), a.name()))
                .toList();
        return List.of(new WhatsAppMessage.Section("Delivery Areas", rows));
    }

    private List<WhatsAppMessage.Section> resolveSubscriptionPlans() {
        List<WhatsAppMessage.Row> rows = subscriptionCatalog.activePlansForAudience("FF").stream()
                .map(p -> new WhatsAppMessage.Row(p.getCode(), p.getName(),
                        String.format("₹%s/week · %d weeks",
                                p.getWeeklyPrice().stripTrailingZeros().toPlainString(),
                                p.getCommitmentWeeks())))
                .toList();
        return List.of(new WhatsAppMessage.Section("Subscription Plans", rows));
    }

    private List<WhatsAppMessage.Section> resolveSubscriptionOptions(Map<String, Object> context) {
        SubscriptionCatalog.PlanConfig plan = subscriptionCatalog.plan(str(context, "subPlan")).orElse(null);
        if (plan == null) return List.of();
        List<WhatsAppMessage.Row> rows = new ArrayList<>();
        for (int i = 0; i < plan.getOptions().size(); i++) {
            rows.add(new WhatsAppMessage.Row("opt" + i, plan.getOptions().get(i).getLabel()));
        }
        return List.of(new WhatsAppMessage.Section("Choose your bundle", rows));
    }

    private List<WhatsAppMessage.Section> resolveSubscriptionItems(Map<String, Object> context) {
        SubscriptionCatalog.PlanConfig plan = subscriptionCatalog.plan(str(context, "subPlan")).orElse(null);
        if (plan == null) return List.of();
        int optIdx = intVal(context, "subOption", 0);
        int comp = intVal(context, "subComp", 0);
        if (optIdx >= plan.getOptions().size()) return List.of();
        SubscriptionCatalog.OptionConfig option = plan.getOptions().get(optIdx);
        if (comp >= option.getComponents().size()) return List.of();
        SubscriptionCatalog.ComponentConfig component = option.getComponents().get(comp);
        List<WhatsAppMessage.Row> rows = subscriptionCatalog.chooseFrom(component.getType(), plan.getTier()).stream()
                .map(name -> new WhatsAppMessage.Row(name, name))
                .toList();
        return List.of(new WhatsAppMessage.Section("Choose your " + typeLabel(component.getType()), rows));
    }

    private List<WhatsAppMessage.Section> resolveSubscriptionDays() {
        DayOfWeek[] days = { DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY };
        List<WhatsAppMessage.Row> rows = new ArrayList<>();
        for (DayOfWeek d : days) {
            rows.add(new WhatsAppMessage.Row(d.name(), d.getDisplayName(TextStyle.FULL, Locale.ENGLISH)));
        }
        return List.of(new WhatsAppMessage.Section("Choose delivery day", rows));
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case "LOAF" -> "loaf";
            case "ROLL" -> "rolls";
            case "SWEET" -> "sweet roll";
            default -> "item";
        };
    }

    private static String str(Map<String, Object> ctx, String key) {
        Object v = ctx != null ? ctx.get(key) : null;
        return v != null ? v.toString() : null;
    }

    private static int intVal(Map<String, Object> ctx, String key, int def) {
        String s = str(ctx, key);
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private List<WhatsAppMessage.Section> resolveDeliveryDates(Map<String, Object> context, boolean fnf) {
        Object orderIdVal = context != null ? context.get("orderId") : null;
        Long orderId = orderIdVal != null ? Long.parseLong(orderIdVal.toString()) : null;
        DeliveryRules.CartFlags flags = deliveryRules.flagsForOrder(orderId, fnf);

        // Earliest date accounts for cutoff + the 48h bagel lead time.
        LocalDate start = deliveryRules.earliestDate(flags);

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("EEEE, d MMMM");
        List<WhatsAppMessage.Row> rows = new ArrayList<>();
        LocalDate candidate = start;
        LocalDate windowEnd = start.plusDays(7); // only show dates within the coming week
        while (candidate.isBefore(windowEnd)) {
            if (deliveryRules.isAvailable(candidate, flags)) {
                rows.add(new WhatsAppMessage.Row(
                        candidate.toString(),          // id: "2026-06-21"
                        candidate.format(fmt)));       // title: "Saturday, 21 June"
            }
            candidate = candidate.plusDays(1);
        }
        return List.of(new WhatsAppMessage.Section("Choose Delivery Date", rows));
    }
}
