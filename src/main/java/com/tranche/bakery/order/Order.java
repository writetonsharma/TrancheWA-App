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

    @Column(name = "source", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private OrderSource source = OrderSource.RETAIL;

    // Seller identity for a commercial invoice/receipt (company proprietorship vs individual). RETAIL orders keep COMPANY.
    @Column(name = "seller_profile", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private SellerProfileType sellerProfile = SellerProfileType.COMPANY;

    // Whether order-status changes send WhatsApp updates to the customer. Retail = true; commercial is opt-in per order.
    @Column(name = "notify_customer", nullable = false)
    private boolean notifyCustomer = true;

    // Bill/invoice number for manually-created commercial orders (TRB-INV-...); null for retail.
    @Column(name = "invoice_number", unique = true, length = 30)
    private String invoiceNumber;

    // Optional buyer business name shown on a commercial invoice.
    @Column(name = "business_name", length = 150)
    private String businessName;

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

    @Column(name = "batch_discount_amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal batchDiscountAmount = BigDecimal.ZERO;

    @Column(name = "batch_discount_label", length = 200)
    private String batchDiscountLabel;

    // Account credit applied to this order (snapshot); deducted from the customer's balance on approval.
    @Column(name = "credit_applied", precision = 10, scale = 2, nullable = false)
    private BigDecimal creditApplied = BigDecimal.ZERO;

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

    // Non-null when this order was generated from a prepaid subscription (billed ₹0, no payment due).
    @Column(name = "subscription_id")
    private Long subscriptionId;

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
