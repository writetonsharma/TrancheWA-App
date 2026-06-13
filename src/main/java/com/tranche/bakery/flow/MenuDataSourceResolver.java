package com.tranche.bakery.flow;

import com.tranche.bakery.delivery.DeliveryAreaLoader;
import com.tranche.bakery.menu.MenuCategory;
import com.tranche.bakery.menu.MenuCategoryRepository;
import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.menu.MenuItemRepository;
import com.tranche.bakery.whatsapp.WhatsAppMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MenuDataSourceResolver implements DataSourceResolver {

    private final MenuCategoryRepository categoryRepository;
    private final MenuItemRepository itemRepository;
    private final DeliveryAreaLoader deliveryAreaLoader;

    @Override
    public List<WhatsAppMessage.Section> resolve(String dataSource, Map<String, Object> context) {
        return switch (dataSource) {
            case "MENU_CATEGORIES" -> resolveCategories();
            case "MENU_ITEMS"      -> resolveItems(context);
            case "DELIVERY_AREAS"  -> resolveDeliveryAreas();
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
                .map(i -> new WhatsAppMessage.Row(
                        i.getId().toString(),
                        i.getName(),
                        String.format("₹%.0f", i.getPrice())))
                .toList();
        return List.of(new WhatsAppMessage.Section(category.getName(), rows));
    }

    private List<WhatsAppMessage.Section> resolveDeliveryAreas() {
        List<WhatsAppMessage.Row> rows = deliveryAreaLoader.getAreas().stream()
                .map(a -> new WhatsAppMessage.Row(a.id(), a.name()))
                .toList();
        return List.of(new WhatsAppMessage.Section("Delivery Areas", rows));
    }
}
