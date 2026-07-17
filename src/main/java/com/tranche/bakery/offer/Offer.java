package com.tranche.bakery.offer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A promotional offer, managed from the admin Offers page. Rotating or pausing an
 * offer of an existing kind is data-only (toggle active or edit fields); only a
 * brand-new kind needs code. The evaluation lives in PromotionEngine.
 */
@Entity
@Table(name = "offers")
@Getter @Setter @NoArgsConstructor
public class Offer {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private OfferKind kind;

    @Column(name = "stack_group", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private OfferGroup stackGroup;

    /** Used by PERCENT_OFF. */
    @Column(precision = 5, scale = 2)
    private BigDecimal percent;

    /** Used by FREE_DELIVERY_OVER / FREE_ITEM_OVER: minimum discounted subtotal to qualify. */
    @Column(name = "threshold_amount", precision = 10, scale = 2)
    private BigDecimal thresholdAmount;

    /** Used by FREE_ITEM_OVER: what the complimentary line reads as. */
    @Column(name = "gift_label", length = 200)
    private String giftLabel;

    /** Short customer-facing label shown in the order summary. */
    @Column(nullable = false, length = 100)
    private String label;

    /** Tie-breaker within a group when values are equal; higher wins. */
    @Column(nullable = false)
    private int priority = 0;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "starts_at")
    private LocalDateTime startsAt;

    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    /** Live = active and within its optional [startsAt, endsAt) window. */
    public boolean isLiveAt(LocalDateTime now) {
        if (!active) return false;
        if (startsAt != null && now.isBefore(startsAt)) return false;
        if (endsAt != null && !now.isBefore(endsAt)) return false;
        return true;
    }
}
