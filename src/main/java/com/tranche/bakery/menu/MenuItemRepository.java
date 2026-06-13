package com.tranche.bakery.menu;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {
    Optional<MenuItem> findByCategoryAndName(MenuCategory category, String name);
    List<MenuItem> findAllByCategoryAndActiveTrueOrderByDisplayOrderAsc(MenuCategory category);
}
