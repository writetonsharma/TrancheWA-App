package com.tranche.bakery.offer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A price-band rule for the dynamic batch discount. An item qualifies for the extra
 * {@code percent} off once its booked units for a delivery day reach
 * {@code thresholdUnits}. Bands are matched by the item list price: min inclusive,
 * max exclusive (null max = no upper bound). Tunable data-only from the admin/SQL.
 */
@Entity
@Table(name = "batch_discount_bands")
@Getter @Setter @NoArgsConstructor
public class BatchDiscountBand {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(name = "min_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal minPrice = BigDecimal.ZERO;

    /** Exclusive upper bound; null means no upper bound. */
    @Column(name = "max_price", precision = 10, scale = 2)
    private BigDecimal maxPrice;

    @Column(name = "threshold_units", nullable = false)
    private int thresholdUnits;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal percent;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    /** Whether the given list price falls in this band (min inclusive, max exclusive). */
    public boolean matchesPrice(BigDecimal price) {
        if (price == null) return false;
        if (price.compareTo(minPrice) < 0) return false;
        return maxPrice == null || price.compareTo(maxPrice) < 0;
    }
}
