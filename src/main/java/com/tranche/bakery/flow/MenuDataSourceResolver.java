package com.tranche.bakery.flow;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.tranche.bakery.delivery.DeliveryAreaLoader;
import com.tranche.bakery.menu.MenuCategory;
import com.tranche.bakery.menu.MenuCategoryRepository;
import com.tranche.bakery.menu.MenuItemRepository;
import com.tranche.bakery.whatsapp.WhatsAppMessage;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MenuDataSourceResolver implements DataSourceResolver {

    private final MenuCategoryRepository categoryRepository;
    private final MenuItemRepository itemRepository;
    private final DeliveryAreaLoader deliveryAreaLoader;

    @org.springframework.beans.factory.annotation.Value("${bakery.order.cutoff-hour}")
    private int cutoffHour;

    @Override
    public List<WhatsAppMessage.Section> resolve(String dataSource, Map<String, Object> context) {
        return switch (dataSource) {
            case "MENU_CATEGORIES" -> resolveCategories();
            case "MENU_ITEMS"      -> resolveItems(context);
            case "DELIVERY_AREAS"  -> resolveDeliveryAreas();
            case "DELIVERY_DATES"  -> resolveDeliveryDates();
            default -> throw new IllegalArgumentException("Unknown dataSource: " + dataSource);
        };
    }

    private List<WhatsAppMessage.Section> resolveCategories() {
        List<WhatsAppMessage.Row> rows = categoryRepository
                .findAllByActiveTrueOrderByDisplayOrderAsc()
                .stream()
                .map(c -> new WhatsAppMessage.Row(c.getId().toString(), c.getName()))
                .toList();
        return List.of(new WhatsAppMessage.Section("Categories", rows));
    }

    private List<WhatsAppMessage.Section> resolveItems(Map<String, Object> context) {
        Object categoryIdVal = context != null ? context.get("categoryId") : null;
        if (categoryIdVal == null) return List.of();

        Long categoryId = Long.parseLong(categoryIdVal.toString());
        MenuCategory category = categoryRepository.findById(categoryId).orElse(null);
        if (category == null) return List.of();

        List<WhatsAppMessage.Row> rows = itemRepository
                .findAllByCategoryAndActiveTrueOrderByDisplayOrderAsc(category)
                .stream()
                .map(i -> {
                    String price = String.format("₹%.0f", i.getPrice());
                    String desc = (i.getDescription() != null && !i.getDescription().isBlank())
                            ? price + " · " + i.getDescription()
                            : price;
                    return new WhatsAppMessage.Row(i.getId().toString(), i.getName(), desc);
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

    private List<WhatsAppMessage.Section> resolveDeliveryDates() {
        // Start from tomorrow; if after cutoff, start from day after tomorrow
        LocalDate start = LocalDate.now().plusDays(
                LocalTime.now().getHour() >= cutoffHour ? 2 : 1);

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("EEEE, d MMMM");
        List<WhatsAppMessage.Row> rows = new ArrayList<>();
        LocalDate candidate = start;
        while (rows.size() < 7) {
            // Skip Mondays — no delivery (nothing baked on Sunday)
            if (candidate.getDayOfWeek() != DayOfWeek.MONDAY) {
                rows.add(new WhatsAppMessage.Row(
                        candidate.toString(),          // id: "2026-06-21"
                        candidate.format(fmt)));       // title: "Saturday, 21 June"
            }
            candidate = candidate.plusDays(1);
        }
        return List.of(new WhatsAppMessage.Section("Choose Delivery Date", rows));
    }
}
