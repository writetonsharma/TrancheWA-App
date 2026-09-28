package com.tranche.bakery.payment;

import com.tranche.bakery.order.Order;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "payments")
@Getter @Setter @NoArgsConstructor
public class Payment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(length = 100)
    private String upiId;

    // The amount we actually asked for. May be a token amount under payment test mode, so the
    // gateway webhook must verify against this rather than the order total.
    @Column(precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(length = 20)
    private String provider;

    @Column(name = "gateway_link_id", length = 100)
    private String gatewayLinkId;

    // Kept so a repeat payment prompt re-sends this link rather than creating a second one.
    @Column(name = "gateway_link_url", length = 255)
    private String gatewayLinkUrl;

    @Column(name = "gateway_payment_id", length = 100)
    private String gatewayPaymentId;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(columnDefinition = "BYTEA")
    private byte[] qrImageData;

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<PaymentScreenshot> screenshots;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = LocalDateTime.now(); }
}
