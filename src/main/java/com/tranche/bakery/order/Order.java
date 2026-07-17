package com.tranche.bakery.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.tranche.bakery.conversation.WhatsappConversation;
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

@Entity
@Table(name = "orders")
@Getter @Setter @NoArgsConstructor
public class Order {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id")
    private WhatsappConversation conversation;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private OrderStatus status = OrderStatus.DRAFT;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private FulfillmentType fulfillmentType = FulfillmentType.DELIVERY;

    @Column(precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal deliveryCharge = BigDecimal.ZERO;

    @Column(name = "discount_amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "discount_label", length = 100)
    private String discountLabel;

    @Column(name = "gift_label", length = 200)
    private String giftLabel;

    @Column(nullable = false)
    private boolean cutoffWarned = false;

    @Column(name = "cutoff_cancelled", nullable = false)
    private boolean cutoffCancelled = false;

    @Column(unique = true, length = 20)
    private String orderNumber;

    @Column(name = "delivery_date")
    private java.time.LocalDate deliveryDate;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(columnDefinition = "TEXT")
    private String deliveryAddress;

    @Column(length = 20)
    private String deliveryPreference;

    @Column(length = 20)
    private String loafPreference;

    @Column(precision = 9, scale = 6)
    private BigDecimal locationLat;

    @Column(precision = 9, scale = 6)
    private BigDecimal locationLng;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<OrderItem> items;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = LocalDateTime.now(); }
}
