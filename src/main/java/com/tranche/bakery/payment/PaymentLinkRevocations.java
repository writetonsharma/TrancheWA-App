package com.tranche.bakery.payment;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Durable revocation outbox. Provider I/O never runs in a transaction synchronization. */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentLinkRevocations {
    private final JdbcTemplate jdbc;
    private final RazorpayService gateway;
    private final PlatformTransactionManager transactions;

    private record Binding(Long paymentId, Long orderId, String linkId) { }

    /** Caller owns the mutation transaction and its capacity/order locks. */
    public void fence(Payment payment) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Revocation fence requires the mutation transaction");
        }
        jdbc.update("""
                INSERT INTO payment_link_revocations(payment_id, order_id, gateway_link_id)
                VALUES (?, ?, ?) ON CONFLICT (payment_id, gateway_link_id) DO NOTHING
                """, payment.getId(), payment.getOrder().getId(), payment.getGatewayLinkId());
    }

    public void retryForOrder(Long orderId) {
        retry(orderId);
    }

    /** Crash/failure recovery, including cancelled orders that never receive another prompt. */
    @Scheduled(fixedDelayString = "${bakery.payment.revocation-retry-ms:60000}")
    public void retryPending() {
        retry(null);
    }

    private void retry(Long orderId) {
        // An ambient transaction must finish normally; suspending it would retain its locks.
        // In particular, never run in afterCommit/afterCompletion before resource cleanup.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) return;
        List<Binding> bindings = jdbc.query("""
                SELECT r.payment_id, r.order_id, r.gateway_link_id
                FROM payment_link_revocations r JOIN payments p ON p.id = r.payment_id
                WHERE p.status = 'PENDING'
                """ + (orderId == null ? "" : " AND r.order_id = ?"),
                (rs, row) -> new Binding(rs.getLong(1), rs.getLong(2), rs.getString(3)),
                orderId == null ? new Object[0] : new Object[] { orderId });
        if (bindings.isEmpty() || !gateway.isConfigured()) return;
        for (Binding binding : bindings) {
            try {
                try {
                    gateway.cancelPaymentLink(binding.linkId());
                } catch (Exception cancellationFailure) {
                    // A previous successful cancel may have lost its HTTP response or DB
                    // completion. A fresh terminal provider state is also revocation proof.
                    // Paid/partial/unknown states are NEVER treated as a successful revoke.
                    RazorpayService.LinkState state = gateway.fetchPaymentLink(binding.linkId());
                        if (state == null || state.amountPaid() == null || state.amountPaid().signum() != 0
                            || !("cancelled".equals(state.status())
                            || "expired".equals(state.status()))) throw cancellationFailure;
                }
                TransactionTemplate transaction = new TransactionTemplate(transactions);
                transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                transaction.executeWithoutResult(status -> {
                    // Atomic predicate, not a stale entity save. Never clear a replacement or
                    // overwrite a concurrently captured payment. No global capacity lock needed.
                    jdbc.update("""
                            UPDATE payments SET gateway_link_id = NULL, gateway_link_url = NULL,
                                updated_at = now()
                            WHERE id = ? AND order_id = ? AND gateway_link_id = ? AND status = 'PENDING'
                            """, binding.paymentId(), binding.orderId(), binding.linkId());
                    jdbc.update("""
                            DELETE FROM payment_link_revocations
                            WHERE payment_id = ? AND order_id = ? AND gateway_link_id = ?
                            """, binding.paymentId(), binding.orderId(), binding.linkId());
                });
            } catch (Exception ex) {
                // No outcome inference or TTL: failure retains both fence and binding for retry.
                log.warn("Payment link {} for order {} remains fenced: {}",
                        binding.linkId(), binding.orderId(), ex.getMessage());
            }
        }
    }
}