package com.tranche.bakery.menu;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;

@Entity
@Table(name = "menu_categories")
@Getter @Setter @NoArgsConstructor
public class MenuCategory {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean active = true;

    // False for admin-billing-only categories such as gift hampers: hidden from the bot menu
    // and website catalogue, excluded from the bake list and daily capacity.
    @Column(name = "retail_visible", nullable = false)
    private boolean retailVisible = true;

    @OneToMany(mappedBy = "category", fetch = FetchType.LAZY)
    private List<MenuItem> items;
}
