package com.tranche.bakery.customer;

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

    private LocalDateTime overrideExpiresAt;

    @Column(columnDefinition = "TEXT")
    private String overrideNote;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    public boolean hasActiveOverride() {
        if (pricingOverride == null) return false;
        if (overrideExpiresAt != null && overrideExpiresAt.isBefore(LocalDateTime.now())) return false;
        return true;
    }
}
