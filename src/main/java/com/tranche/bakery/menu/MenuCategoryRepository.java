package com.tranche.bakery.menu;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MenuCategoryRepository extends JpaRepository<MenuCategory, Long> {
    Optional<MenuCategory> findByName(String name);
    List<MenuCategory> findAllByActiveTrueOrderByDisplayOrderAsc();
}
