package com.tranche.bakery.payment;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_screenshots")
@Getter @Setter @NoArgsConstructor
public class PaymentScreenshot {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(length = 255)
    private String whatsappMediaId;

    @Column(columnDefinition = "TEXT")
    private String ocrRaw;

    @Column(precision = 10, scale = 2)
    private BigDecimal ocrAmount;

    @Column(length = 100)
    private String ocrUpiId;

    @Column(length = 50)
    private String ocrStatus;

    @Column(length = 100)
    private String ocrTransactionRef;

    @Column(length = 20)
    private String ocrConfidence;

    @Column(columnDefinition = "TEXT")
    private String reviewNotes;

    @Column(nullable = false, updatable = false)
    private LocalDateTime receivedAt = LocalDateTime.now();
}
