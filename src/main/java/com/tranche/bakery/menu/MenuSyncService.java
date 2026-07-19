package com.tranche.bakery.menu;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class MenuSyncService implements ApplicationRunner {

    private final MenuCategoryRepository categoryRepository;
    private final MenuItemRepository itemRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        var resource = new ClassPathResource("menu.json");
        MenuJson config = objectMapper.readValue(resource.getInputStream(), MenuJson.class);

        Set<Long> seenCategoryIds = new HashSet<>();
        Set<Long> seenItemIds = new HashSet<>();

        for (MenuJson.CategoryJson catJson : config.getCategories()) {
            MenuCategory category = categoryRepository.findByName(catJson.getName())
                    .orElseGet(() -> {
                        MenuCategory c = new MenuCategory();
                        c.setName(catJson.getName());
                        return c;
                    });
            category.setDisplayOrder(catJson.getDisplayOrder());
            category.setActive(true);
            categoryRepository.save(category);
            seenCategoryIds.add(category.getId());

            for (MenuJson.ItemJson itemJson : catJson.getItems()) {
                MenuItem item = itemRepository.findByCategoryAndName(category, itemJson.getName())
                        .orElseGet(() -> {
                            MenuItem i = new MenuItem();
                            i.setCategory(category);
                            i.setName(itemJson.getName());
                            return i;
                        });
                item.setPrice(itemJson.getPrice());
                item.setDisplayOrder(itemJson.getDisplayOrder());
                item.setListTitle(itemJson.getListTitle());
                if (itemJson.getDescription() != null) {
                    item.setDescription(itemJson.getDescription());
                }
                item.setActive(true);
                itemRepository.save(item);
                seenItemIds.add(item.getId());
            }
        }

        // Reconcile: menu.json is the source of truth. Deactivate any active item or
        // category that is no longer present, so removed/renamed entries (e.g. an old
        // bagel variant that was superseded) stop appearing in the live menu.
        int deactivatedItems = 0;
        for (MenuItem item : itemRepository.findAll()) {
            if (item.isActive() && !seenItemIds.contains(item.getId())) {
                item.setActive(false);
                itemRepository.save(item);
                deactivatedItems++;
                log.info("Deactivated stale menu item: {}", item.getName());
            }
        }
        int deactivatedCategories = 0;
        for (MenuCategory category : categoryRepository.findAll()) {
            if (category.isActive() && !seenCategoryIds.contains(category.getId())) {
                category.setActive(false);
                categoryRepository.save(category);
                deactivatedCategories++;
                log.info("Deactivated stale menu category: {}", category.getName());
            }
        }
        log.info("Menu sync complete: {} categories loaded, {} stale items and {} stale categories deactivated",
                config.getCategories().size(), deactivatedItems, deactivatedCategories);
    }

    @Data
    static class MenuJson {
        private List<CategoryJson> categories;

        @Data
        static class CategoryJson {
            private String name;
            private int displayOrder;
            private List<ItemJson> items;
        }

        @Data
        static class ItemJson {
            private String name;
            private String listTitle;
            private BigDecimal price;
            private int displayOrder;
            private String description;
        }
    }
}
