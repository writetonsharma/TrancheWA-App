package com.tranche.bakery.customer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "customers")
@Getter @Setter @NoArgsConstructor
public class Customer {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String phone;

    @Column(length = 100)
    private String name;

    @Column(length = 100)
    private String deliveryArea;

    @Column(columnDefinition = "TEXT")
    private String deliveryAddress;

    @Column(precision = 9, scale = 6)
    private BigDecimal locationLat;

    @Column(precision = 9, scale = 6)
    private BigDecimal locationLng;

    @Column(precision = 10, scale = 2)
    private BigDecimal pricingOverride;

    @Column(nullable = false)
    private boolean freeDelivery = false;

    // True once the admin adds this customer to the F&F list with subscription access — the gate
    // that reveals the self-serve Subscribe flow. Independent of whether they hold flat pricing.
    @Column(nullable = false)
    private boolean subscriptionEligible = false;

    private LocalDateTime overrideExpiresAt;

    @Column(columnDefinition = "TEXT")
    private String overrideNote;

    // Last time this customer messaged us — anchors WhatsApp's 24h free-form window.
    private LocalDateTime lastInboundAt;

    // Flat per-unit price per menu category (by category name). A category entry wins
    // over pricingOverride; a category with no entry falls back to the item's list price.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "customer_category_prices", joinColumns = @JoinColumn(name = "customer_id"))
    @MapKeyColumn(name = "category")
    @Column(name = "flat_price", precision = 10, scale = 2)
    private Map<String, BigDecimal> categoryPrices = new HashMap<>();

    // Flat per-unit price per menu item (by item name). An item entry wins over both the
    // category price and pricingOverride; an item with no entry falls back to those.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "customer_item_prices", joinColumns = @JoinColumn(name = "customer_id"))
    @MapKeyColumn(name = "item_name")
    @Column(name = "flat_price", precision = 10, scale = 2)
    private Map<String, BigDecimal> itemPrices = new HashMap<>();

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    public boolean hasActiveOverride() {
        boolean hasAny = pricingOverride != null
                || (categoryPrices != null && !categoryPrices.isEmpty())
                || (itemPrices != null && !itemPrices.isEmpty());
        if (!hasAny) return false;
        if (overrideExpiresAt != null && overrideExpiresAt.isBefore(LocalDateTime.now())) return false;
        return true;
    }

    // A customer with any live pricing override, or one the admin has flagged for subscription,
    // is treated as Friends & Family: drives the F&F greeting and the relaxed weekend-only filter.
    public boolean isFriendsAndFamily() {
        return hasActiveOverride() || subscriptionEligible;
    }

    // Flat per-unit price to charge for an item under an active override, or null to use the
    // item's normal list price. Precedence: item price → category price → all-items override.
    public BigDecimal unitPriceFor(String itemName, String categoryName) {
        if (!hasActiveOverride()) return null;
        if (itemName != null && itemPrices != null) {
            BigDecimal itemPrice = itemPrices.get(itemName);
            if (itemPrice != null) return itemPrice;
        }
        if (categoryName != null && categoryPrices != null) {
            BigDecimal categoryPrice = categoryPrices.get(categoryName);
            if (categoryPrice != null) return categoryPrice;
        }
        return pricingOverride;
    }
}
