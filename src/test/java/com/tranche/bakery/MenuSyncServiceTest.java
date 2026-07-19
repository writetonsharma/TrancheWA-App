package com.tranche.bakery;

import com.tranche.bakery.menu.MenuCategory;
import com.tranche.bakery.menu.MenuCategoryRepository;
import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.menu.MenuItemRepository;
import com.tranche.bakery.menu.MenuSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that menu.json is the single source of truth: items no longer present
 * are deactivated on sync, while current ones stay active. Guards against stale
 * entries (e.g. a superseded bagel/brioche variant) lingering in the live menu.
 */
@SpringBootTest
@ActiveProfiles("test")
class MenuSyncServiceTest {

    @Autowired private MenuSyncService menuSyncService;
    @Autowired private MenuCategoryRepository categoryRepository;
    @Autowired private MenuItemRepository itemRepository;

    @Test
    void sync_deactivatesItemsNotInMenuJson() throws Exception {
        // Seed a stale, active item that does not exist in menu.json, in a real category.
        MenuCategory category = categoryRepository.findAllByActiveTrueOrderByDisplayOrderAsc().get(0);
        MenuItem stale = new MenuItem();
        stale.setCategory(category);
        stale.setName("Discontinued Brioche Test Loaf");
        stale.setPrice(new BigDecimal("999.00"));
        stale.setDisplayOrder(99);
        stale.setActive(true);
        stale = itemRepository.save(stale);
        Long staleId = stale.getId();

        // Re-run the sync (idempotent; runs on startup in production).
        menuSyncService.run(null);

        // The stale item is deactivated...
        MenuItem reloaded = itemRepository.findById(staleId).orElseThrow();
        assertThat(reloaded.isActive())
                .as("item absent from menu.json should be deactivated")
                .isFalse();

        // ...and it no longer appears among the category's active items.
        assertThat(itemRepository.findAllByCategoryAndActiveTrueOrderByDisplayOrderAsc(category))
                .as("deactivated item should not surface in the live menu")
                .noneMatch(i -> i.getId().equals(staleId));

        // Genuine menu.json items remain active.
        assertThat(itemRepository.findAll())
                .filteredOn(MenuItem::isActive)
                .as("current menu items remain active")
                .isNotEmpty();
    }
}
