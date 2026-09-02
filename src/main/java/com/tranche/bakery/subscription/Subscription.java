package com.tranche.bakery.subscription;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.tranche.bakery.customer.Customer;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A customer's prepaid weekly subscription. Plan details (price, tier, items) are SNAPSHOT here at
 * signup so later edits to subscriptions.json never change an in-flight subscription.
 */
@Entity
@Table(name = "subscriptions")
@Getter @Setter @NoArgsConstructor
public class Subscription {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "plan_code", nullable = false, length = 50)
    private String planCode;

    @Column(name = "plan_name", nullable = false, length = 100)
    private String planName;

    @Column(nullable = false, length = 20)
    private String tier;

    @Column(name = "weekly_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal weeklyPrice;

    @Column(name = "delivery_charge", nullable = false, precision = 10, scale = 2)
    private BigDecimal deliveryCharge;

    // Total prepaid upfront (weeklyPrice + deliveryCharge) × commitmentWeeks, snapshot at signup.
    @Column(name = "upfront_amount", precision = 10, scale = 2)
    private BigDecimal upfrontAmount;

    // À la carte value of the weekly bundle (Σ item list price × portion), snapshot at signup for the savings line.
    @Column(name = "regular_value", precision = 10, scale = 2)
    private BigDecimal regularValue;

    // WhatsApp media id of the payment screenshot the customer shared, pending admin verification.
    @Column(name = "payment_screenshot_media_id", length = 255)
    private String paymentScreenshotMediaId;

    @Column(name = "commitment_weeks", nullable = false)
    private int commitmentWeeks;

    // Free bonus delivery weeks on top of the paid commitment weeks.
    @Column(name = "bonus_weeks", nullable = false)
    private int bonusWeeks;

    @Column(name = "delivery_day", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private DayOfWeek deliveryDay;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private SubscriptionStatus status = SubscriptionStatus.PENDING_PAYMENT;

    @OneToMany(mappedBy = "subscription", cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    private List<SubscriptionItem> items = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    public void addItem(SubscriptionItem item) {
        item.setSubscription(this);
        this.items.add(item);
    }
}
