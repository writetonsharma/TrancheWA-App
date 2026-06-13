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
import java.util.List;

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
                item.setActive(true);
                itemRepository.save(item);
            }
        }
        log.info("Menu sync complete: {} categories loaded", config.getCategories().size());
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
            private BigDecimal price;
            private int displayOrder;
        }
    }
}
