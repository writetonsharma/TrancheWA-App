package com.tranche.bakery.menu;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "menu_items")
@Getter @Setter @NoArgsConstructor
public class MenuItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private MenuCategory category;

    @Column(nullable = false, length = 100)
    private String name;

    // Optional short title for WhatsApp interactive-list rows, whose titles
    // are capped at 24 characters by the WhatsApp Cloud API. When null, the
    // full name is used (and truncated as a safety net). Synced from menu.json.
    @Column(name = "list_title", length = 24)
    private String listTitle;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean active = true;
}
