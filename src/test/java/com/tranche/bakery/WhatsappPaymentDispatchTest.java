package com.tranche.bakery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.node.NullNode;
import com.tranche.bakery.alert.AlertService;
import com.tranche.bakery.order.CapacityLock;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderItem;
import com.tranche.bakery.order.OrderItemRepository;
import com.tranche.bakery.order.OrderService;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.payment.Payment;
import com.tranche.bakery.payment.PaymentRepository;
import com.tranche.bakery.payment.PaymentLinkRevocations;
import com.tranche.bakery.payment.PaymentStatus;
import com.tranche.bakery.payment.QrCodeService;
import com.tranche.bakery.payment.RazorpayService;
import jakarta.persistence.EntityManagerFactory;

/** Real PostgreSQL/flow transactions; all external providers are mocks. */
class WhatsappPaymentDispatchTest extends FlowScenarioBase {

    @Autowired private PlatformTransactionManager transactions;
    @Autowired private OrderService orders;
    @Autowired private OrderItemRepository orderItems;
    @Autowired private PaymentRepository payments;
    @Autowired private EntityManagerFactory entityManagers;
    @Autowired private Environment environment;
    @Autowired private PaymentLinkRevocations revocations;
    @MockitoSpyBean private CapacityLock capacity;
    @MockitoBean private RazorpayService gateway;
    @MockitoBean private QrCodeService qr;
    @MockitoBean private AlertService alerts;

    private static final RazorpayService.PaymentLink LINK =
            new RazorpayService.PaymentLink("plink_mock", "https://example.invalid/pay/mock");

    @BeforeEach
    void configureGateway() throws Exception {
        when(gateway.isGatewayMode()).thenReturn(true);
        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any()))
                .thenReturn(LINK);
    }

    @Test
    void blockingGatewayRunsAfterCommitAndCannotHoldCapacity() throws Exception {
        Order draft = draft(new BigDecimal("100.00"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(gateway.createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any()))
                .thenAnswer(invocation -> {
                    assertReservationCommitted(draft);
                    entered.countDown();
                    await(release);
                    return LINK;
                });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var confirm = executor.submit(() -> flowEngine.handle(customer, conversation,
                    "text", "confirm", NullNode.getInstance()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            // Same real mutex used by independent WEB acceptance/status and payment webhooks.
            // This must finish WHILE the provider is blocked, not after its five-second lock timeout.
            executor.submit(this::acquireCapacity).get(3, TimeUnit.SECONDS);
            assertThat(confirm.isDone()).isFalse();
            release.countDown();
            confirm.get(10, TimeUnit.SECONDS);
            assertThat(paymentLink(draft)).isEqualTo(LINK.id());
            reloadConversation();
            assertState("PAYMENT_PENDING");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentPaymentPromptsCommitOneClaimAndDispatchExactlyOnce() throws Exception {
        Order draft = draft(new BigDecimal("100.00"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(gateway.createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any()))
                .thenAnswer(invocation -> {
                    assertReservationCommitted(draft);
                    assertThat(dispatchState(draft)).isEqualTo("CREATING");
                    assertThat(jdbcTemplate.queryForObject(
                            "SELECT provider_reference FROM whatsapp_payment_dispatches WHERE order_id = ?",
                            String.class, draft.getId())).isEqualTo(invocation.getArgument(2, String.class));
                    entered.countDown();
                    await(release);
                    return LINK;
                });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> flowEngine.handle(customer, conversation,
                    "text", "confirm", NullNode.getInstance()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                var fresh = conversationRepository.findById(Objects.requireNonNull(conversation.getId())).orElseThrow();
                flowEngine.handle(customer, fresh, "text", "paynow_" + draft.getId(), NullNode.getInstance());
                acquireCapacity();
            });
            // Second prompt must finish while the first provider is still blocked.
            second.get(3, TimeUnit.SECONDS);
            verify(gateway, times(1)).createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any());
            assertThat(payments.count()).isZero();
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(dispatchState(draft)).isEqualTo("COMPLETE");
        assertThat(paymentLink(draft)).isEqualTo(LINK.id());
        reloadConversation();
        send("paynow_" + draft.getId());
        verify(gateway, times(1)).createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any());
    }

    @Test
    void claimCommitRollbackSuppressesProviderDispatchAndAllowsFreshClaim() throws Exception {
        Order draft = draft(new BigDecimal("100.00"));
        AtomicInteger phases = new AtomicInteger();
        CapacityLock target = AopTestUtils.getUltimateTargetObject(Objects.requireNonNull(capacity));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (phases.incrementAndGet() == 2) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("mock dispatch claim commit failure");
                    }
                });
            }
            return null;
        }).when(target).acquire();

        send("confirm");

        assertState("PAYMENT_PENDING");
        assertOrderStatus(draft.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM whatsapp_payment_dispatches", Long.class)).isZero();
        assertThat(payments.count()).isZero();
        verify(gateway, never()).createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any());
        verifyNoInteractions(qr, whatsAppClient, alerts);

        send("paynow_" + draft.getId());
        assertThat(dispatchState(draft)).isEqualTo("COMPLETE");
        assertThat(paymentLink(draft)).isEqualTo(LINK.id());
        verify(gateway, times(1)).createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any());
    }

    @Test
    void orphanedCreatingClaimCannotBeRedispatchedAfterCrashOrAgeing() throws Exception {
        Order draft = draft(new BigDecimal("100.00"));
        jdbcTemplate.update("UPDATE orders SET status = 'PENDING_CONFIRMATION' WHERE id = ?", draft.getId());
        conversation.setState("PAYMENT_PENDING");
        conversation = conversationRepository.save(conversation);
        jdbcTemplate.update("""
                INSERT INTO whatsapp_payment_dispatches(order_id, provider_reference, state, updated_at)
                VALUES (?, 'wa-orphaned-provider-request', 'CREATING', now() - interval '7 days')
                """, draft.getId());

        send("paynow_" + draft.getId());
        send("confirm");

        assertThat(dispatchState(draft)).isEqualTo("CREATING");
        assertThat(payments.count()).isZero();
        verify(gateway, never()).createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any());
        verifyNoInteractions(qr);
    }

    @Test
    void preMigrationReusableLinkNeedsNeitherClaimNorCheckoutOptIn() throws Exception {
        Order draft = draft(new BigDecimal("100.00"));
        jdbcTemplate.update("UPDATE orders SET status = 'PENDING_CONFIRMATION' WHERE id = ?", draft.getId());
        Payment payment = new Payment();
        payment.setOrder(draft);
        payment.setProvider("RAZORPAY");
        payment.setAmount(new BigDecimal("100.00"));
        payment.setGatewayLinkId(LINK.id());
        payment.setGatewayLinkUrl(LINK.shortUrl());
        payments.save(payment);
        conversation.setState("PAYMENT_PENDING");
        conversation = conversationRepository.save(conversation);
        assertThat(environment.getProperty("bakery.web.enabled", Boolean.class, false)).isFalse();
        assertThat(environment.getProperty("bakery.web.checkout.enabled", Boolean.class, false)).isFalse();

        send("paynow_" + draft.getId());

        assertThat(paymentLink(draft)).isEqualTo(LINK.id());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM whatsapp_payment_dispatches", Long.class)).isZero();
        assertThat(sentTexts).anyMatch(text -> text.contains(LINK.shortUrl()));
        verify(gateway, never()).createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any());
        verifyNoInteractions(qr);
    }

    @Test
    void blockingWhatsappHasDurablePaymentAndRetryReusesTheSameLink() throws Exception {
        Order draft = draft(new BigDecimal("100.00"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertReservationCommitted(draft);
            assertThat(paymentLink(draft)).isEqualTo(LINK.id());
            entered.countDown();
            await(release);
            throw new IllegalStateException("mock WhatsApp delivery failure");
        }).when(whatsAppClient).sendText(eq(customer.getPhone()), anyString());

        var executor = Executors.newFixedThreadPool(2);
        try {
            var confirm = executor.submit(() -> flowEngine.handle(customer, conversation,
                    "text", "confirm", NullNode.getInstance()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            executor.submit(this::acquireCapacity).get(3, TimeUnit.SECONDS);
            assertThat(confirm.isDone()).isFalse();
            release.countDown();
            confirm.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        doAnswer(invocation -> {
            assertOutsideTransaction();
            assertThat(paymentLink(draft)).isEqualTo(LINK.id());
            return null;
        }).when(whatsAppClient).sendText(eq(customer.getPhone()), anyString());
        reloadConversation();
        assertState("PAYMENT_PENDING");
        send("confirm"); // Unrecognised input re-enters the current payment action.
        send("paynow_" + draft.getId());
        verify(gateway, times(1)).createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any());
        assertThat(payments.count()).isEqualTo(1L);
        assertOrderStatus(draft.getId(), OrderStatus.PENDING_CONFIRMATION);
    }

    @Test
    void commitFailureRollsBackReservationAndConversationWithoutAnyProviderDispatch() {
        Order draft = draft(new BigDecimal("100.00"));
        clearInvocations(gateway, qr, whatsAppClient, alerts);
        CapacityLock target = AopTestUtils.getUltimateTargetObject(Objects.requireNonNull(capacity));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("mock flow commit failure");
                }
            });
            return null;
        }).when(target).acquire();

        assertThatThrownBy(() -> send("confirm")).hasMessage("mock flow commit failure");

        reloadConversation();
        assertState("ORDER_CONFIRM");
        assertOrderStatus(draft.getId(), OrderStatus.DRAFT);
        assertThat(orderRepository.findById(Objects.requireNonNull(draft.getId())).orElseThrow().getOrderNumber()).isNull();
        assertThat(payments.count()).isZero();
        verifyNoInteractions(gateway, qr, whatsAppClient, alerts);
    }

    @Test
    void enclosingTransactionIsSuspendedThroughDispatchAndCannotKeepCapacityBound() throws Exception {
        Order draft = draft(new BigDecimal("100.00"));
        when(gateway.createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any()))
                .thenAnswer(invocation -> {
                    assertReservationCommitted(draft);
                    acquireCapacity();
                    return LINK;
                });
        new TransactionTemplate(Objects.requireNonNull(transactions)).executeWithoutResult(status -> {
            send("confirm");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            status.setRollbackOnly(); // Not allowed to undo the independently owned flow commit.
        });
        assertOrderStatus(draft.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(paymentLink(draft)).isEqualTo(LINK.id());
    }

    @Test
    void cancellationDuringGatewayCallDoesNotResurrectOrderOrExposeNewLink() throws Exception {
        Order draft = draft(new BigDecimal("100.00"));
        when(gateway.createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any()))
                .thenAnswer(invocation -> {
                    assertReservationCommitted(draft);
                    assertThat(orders.cancelByIdForCustomer(draft.getId(), customer.getId())).isTrue();
                    return LINK;
                });
        doAnswer(invocation -> {
            assertOutsideTransaction();
            acquireCapacity();
            return null;
        }).when(gateway).cancelPaymentLink(LINK.id());

        send("confirm");

        assertOrderStatus(draft.getId(), OrderStatus.CANCELLED);
        assertThat(payments.count()).isZero();
        assertThat(dispatchState(draft)).isEqualTo("COMPLETE");
        verify(gateway).cancelPaymentLink(LINK.id());
        verify(whatsAppClient, never()).sendText(anyString(), anyString());
    }

    @Test
    void concurrentCartEditDuringGatewayCallCannotPersistAnOldAmount() throws Exception {
        Order draft = draft(new BigDecimal("100.00"));
        Long menuId = orderItems.findAllByOrderId(draft.getId()).get(0).getMenuItem().getId();
        when(gateway.createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any()))
                .thenAnswer(invocation -> {
                    assertReservationCommitted(draft);
                    orders.addItem(draft, menuId, 1);
                    return LINK;
                });

        // Model a real HTTP request's OSIV context. Its managed entities must not mutate
        // the dispatch snapshot in place and make an obsolete provider amount look valid.
        var entityManager = entityManagers.createEntityManager();
        TransactionSynchronizationManager.bindResource(Objects.requireNonNull(entityManagers),
                new EntityManagerHolder(entityManager));
        try {
            send("confirm");
        } finally {
            TransactionSynchronizationManager.unbindResource(Objects.requireNonNull(entityManagers));
            entityManager.close();
        }

        assertThat(payments.count()).isZero();
        assertThat(orderItems.sumQuantityByOrderId(draft.getId())).isEqualTo(2);
        verify(gateway).cancelPaymentLink(LINK.id());
        verify(whatsAppClient, never()).sendText(anyString(), anyString());
    }

    @Test
    void settlementDuringProviderCallCannotBeOverwrittenOrExposedAsPending() throws Exception {
        Order draft = draft(new BigDecimal("100.00"));
        Payment payment = new Payment();
        payment.setOrder(draft);
        payment.setAmount(new BigDecimal("100.00"));
        payments.save(payment);
        when(gateway.createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any()))
                .thenAnswer(invocation -> {
                    assertReservationCommitted(draft);
                    new TransactionTemplate(Objects.requireNonNull(transactions)).executeWithoutResult(status -> {
                        capacity.acquire();
                        jdbcTemplate.update("UPDATE orders SET status = 'CONFIRMED' WHERE id = ?", draft.getId());
                        jdbcTemplate.update("UPDATE payments SET status = 'GATEWAY_CAPTURED' WHERE order_id = ?", draft.getId());
                    });
                    return LINK;
                });
        doAnswer(invocation -> {
            assertOutsideTransaction();
            acquireCapacity();
            return null;
        }).when(gateway).cancelPaymentLink(LINK.id());

        send("confirm");

        assertOrderStatus(draft.getId(), OrderStatus.CONFIRMED);
        var captured = payments.findByOrder(draft).orElseThrow();
        assertThat(captured.getStatus()).isEqualTo(PaymentStatus.GATEWAY_CAPTURED);
        assertThat(captured.getGatewayLinkId()).isNull();
        verify(gateway).cancelPaymentLink(LINK.id());
        verify(whatsAppClient, never()).sendText(anyString(), anyString());
    }

    @Test
    void qrAmountAndImageCommitBeforeUploadAndScreenshotFlowStillWorks() throws Exception {
        Order draft = draft(new BigDecimal("100.00"));
        when(gateway.isGatewayMode()).thenReturn(false);
        byte[] png = new byte[] {1, 2, 3};
        when(qr.generateUpiQrPng(anyString(), anyString(), any(), anyString())).thenReturn(png);
        when(whatsAppClient.uploadMedia(any(), anyString())).thenAnswer(invocation -> {
            assertReservationCommitted(draft);
            acquireCapacity();
            var payment = payments.findByOrder(draft).orElseThrow();
            assertThat(payment.getAmount()).isEqualByComparingTo("100.00");
            assertThat(payment.getQrImageData()).containsExactly(png);
            return "mock-media";
        });
        doAnswer(invocation -> {
            assertReservationCommitted(draft);
            acquireCapacity();
            return null;
        }).when(whatsAppClient).sendImage(anyString(), anyString(), anyString());

        send("confirm");
        verify(whatsAppClient).sendImage(eq(customer.getPhone()), eq("mock-media"), anyString());
        sendImage("mock-screenshot");

        assertState("IDLE");
        assertOrderStatus(draft.getId(), OrderStatus.PAYMENT_SCREENSHOT_RECEIVED);
        verify(gateway, never()).createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any());
    }

    @Test
    void failedPostCommitPaymentSaveNeverExposesAnUnrecordedQrOrManualPayment() {
        Order draft = draft(new BigDecimal("100.00"));
        when(gateway.isGatewayMode()).thenReturn(false);
        AtomicInteger phases = new AtomicInteger();
        CapacityLock target = AopTestUtils.getUltimateTargetObject(Objects.requireNonNull(capacity));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (phases.incrementAndGet() == 2) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("mock payment persistence failure");
                    }
                });
            }
            return null;
        }).when(target).acquire();

        send("confirm");

        assertState("PAYMENT_PENDING");
        assertOrderStatus(draft.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(phases.get()).isEqualTo(2);
        assertThat(payments.count()).isZero();
        verifyNoInteractions(qr, whatsAppClient, alerts);
    }

    @Test
    void ambiguousGatewayTimeoutLeavesDurableUnknownAndNeverRedispatchesOrFallsBackToQr() throws Exception {
        Order draft = draft(new BigDecimal("100.00"));
        when(gateway.createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any()))
                .thenThrow(new java.net.http.HttpTimeoutException("mock response lost after provider accepted request"))
                .thenReturn(LINK);

        send("confirm");

        assertState("PAYMENT_PENDING");
        assertOrderStatus(draft.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(payments.count()).isZero();
        assertThat(dispatchState(draft)).isEqualTo("UNKNOWN");
        verify(alerts).raise(eq("PAYMENT_LINK_FAILED"), anyString(), eq(draft.getId()), eq(customer.getPhone()));
        verifyNoInteractions(qr);

        send("paynow_" + draft.getId());
        send("confirm");
        assertThat(dispatchState(draft)).isEqualTo("UNKNOWN");
        assertThat(payments.count()).isZero();
        verify(gateway, times(1)).createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any());
        verifyNoInteractions(qr);
    }

    @Test
    void failedLinkCommitRevokesOutsideLocksAndFencesAnyRetry() throws Exception {
        Order draft = draft(new BigDecimal("100.00"));
        AtomicInteger phases = new AtomicInteger();
        CapacityLock target = AopTestUtils.getUltimateTargetObject(Objects.requireNonNull(capacity));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (phases.incrementAndGet() == 3) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("mock link commit failure");
                    }
                });
            }
            return null;
        }).when(target).acquire();
        doAnswer(invocation -> {
            assertOutsideTransaction();
            acquireCapacity();
            return null;
        }).when(gateway).cancelPaymentLink(LINK.id());

        send("confirm");
        send("paynow_" + draft.getId());

        assertThat(payments.count()).isZero();
        assertThat(dispatchState(draft)).isEqualTo("UNKNOWN");
        verify(gateway).cancelPaymentLink(LINK.id());
        verify(gateway, times(1)).createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any());
        verifyNoInteractions(qr);
    }

    @Test
    void supersededLinkMustBeRevokedOutsideLocksBeforeNewDispatch() throws Exception {
        Order draft = draft(new BigDecimal("100.00"));
        send("confirm");
        jdbcTemplate.update("UPDATE orders SET total_amount = 200 WHERE id = ?", draft.getId());
        var replacement = new RazorpayService.PaymentLink("plink_replacement", "https://example.invalid/pay/replacement");
        doAnswer(invocation -> {
            assertOutsideTransaction();
            assertThat(dispatchState(draft)).isEqualTo("CREATING");
            acquireCapacity();
            return null;
        }).when(gateway).cancelPaymentLink(LINK.id());
        when(gateway.createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any()))
                .thenAnswer(invocation -> {
                    assertOutsideTransaction();
                    acquireCapacity();
                    verify(gateway).cancelPaymentLink(LINK.id());
                    return replacement;
                });

        send("paynow_" + draft.getId());

        var payment = payments.findByOrder(draft).orElseThrow();
        assertThat(payment.getGatewayLinkId()).isEqualTo(replacement.id());
        assertThat(payment.getAmount()).isEqualByComparingTo("200.00");
        assertThat(dispatchState(draft)).isEqualTo("COMPLETE");
        verify(gateway, times(2)).createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any());
    }

    @Test
    void failedSupersededLinkRevocationCannotOpenAnotherPaymentRoute() throws Exception {
        Order draft = draft(new BigDecimal("100.00"));
        send("confirm");
        jdbcTemplate.update("UPDATE orders SET total_amount = 200 WHERE id = ?", draft.getId());
        doAnswer(invocation -> {
            assertOutsideTransaction();
            acquireCapacity();
            throw new IllegalStateException("mock revocation response lost");
        }).when(gateway).cancelPaymentLink(LINK.id());

        send("paynow_" + draft.getId());
        send("paynow_" + draft.getId());

        assertThat(dispatchState(draft)).isEqualTo("UNKNOWN");
        assertThat(payments.findByOrder(draft).orElseThrow().getGatewayLinkId()).isEqualTo(LINK.id());
        verify(gateway, times(1)).cancelPaymentLink(LINK.id());
        verify(gateway, times(1)).createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any());
        verifyNoInteractions(qr);
    }

    @Test
    void blockingCartEditRevocationCommitsFenceAndReleasesCapacityAndOrderRow() throws Exception {
        Order order = draft(new BigDecimal("100.00"));
        send("confirm");
        Long menuId = orderItems.findAllByOrderId(order.getId()).get(0).getMenuItem().getId();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertOutsideTransaction();
            assertThat(revocationCount(order)).isEqualTo(1);
            assertThat(orderItems.sumQuantityByOrderId(order.getId())).isEqualTo(2);
            entered.countDown();
            await(release);
            return null;
        }).when(gateway).cancelPaymentLink(LINK.id());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var edit = executor.submit(() -> orders.addItem(order, menuId, 1));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            executor.submit(() -> new TransactionTemplate(Objects.requireNonNull(transactions))
                    .executeWithoutResult(status -> {
                        capacity.acquire();
                        jdbcTemplate.queryForObject("SELECT id FROM orders WHERE id = ? FOR UPDATE NOWAIT",
                                Long.class, order.getId());
                    })).get(3, TimeUnit.SECONDS);
            assertThat(edit.isDone()).isFalse();
            assertThat(paymentLink(order)).isEqualTo(LINK.id());
            verify(gateway, times(1)).createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any());
            release.countDown();
            edit.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(revocationCount(order)).isZero();
        assertThat(paymentLink(order)).isNull();
    }

    @Test
    void successfulCartEditRevocationNeverClearsAReplacementBinding() throws Exception {
        Order order = draft(new BigDecimal("100.00"));
        send("confirm");
        doAnswer(invocation -> {
            assertOutsideTransaction();
            acquireCapacity();
            jdbcTemplate.update("""
                    UPDATE payments SET gateway_link_id = 'plink_replacement',
                        gateway_link_url = 'https://example.invalid/replacement'
                    WHERE order_id = ?
                    """, order.getId());
            return null;
        }).when(gateway).cancelPaymentLink(LINK.id());

        orders.revokePaymentLink(order);

        Payment after = payments.findByOrder(order).orElseThrow();
        assertThat(after.getGatewayLinkId()).isEqualTo("plink_replacement");
        assertThat(after.getGatewayLinkUrl()).isEqualTo("https://example.invalid/replacement");
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(revocationCount(order)).isZero();
    }

    @Test
    void successfulRevokeDoesNotClearABindingCapturedWhileProviderWasInFlight() throws Exception {
        Order order = draft(new BigDecimal("100.00"));
        send("confirm");
        doAnswer(invocation -> {
            assertOutsideTransaction();
            jdbcTemplate.update("""
                    UPDATE payments SET status = 'GATEWAY_CAPTURED', gateway_payment_id = 'pay_captured'
                    WHERE order_id = ?
                    """, order.getId());
            return null;
        }).when(gateway).cancelPaymentLink(LINK.id());

        orders.revokePaymentLink(order);

        Payment after = payments.findByOrder(order).orElseThrow();
        assertThat(after.getGatewayLinkId()).isEqualTo(LINK.id());
        assertThat(after.getGatewayLinkUrl()).isEqualTo(LINK.shortUrl());
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.GATEWAY_CAPTURED);
        assertThat(after.getGatewayPaymentId()).isEqualTo("pay_captured");
    }

    @Test
    void rolledBackCancellationRollsBackFenceAndPerformsNoProviderIo() throws Exception {
        Order order = draft(new BigDecimal("100.00"));
        send("confirm");
        clearInvocations(gateway);

        new TransactionTemplate(Objects.requireNonNull(transactions)).executeWithoutResult(status -> {
            orders.cancel(order);
            assertThat(revocationCount(order)).isEqualTo(1);
            revocations.retryPending(); // Even an explicit retry must not suspend retained locks.
            verifyNoInteractions(gateway);
            status.setRollbackOnly();
        });
        revocations.retryPending();

        assertOrderStatus(order.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(revocationCount(order)).isZero();
        assertThat(paymentLink(order)).isEqualTo(LINK.id());
        verifyNoInteractions(gateway);
    }

    @Test
    void failedCartEditRevokeStaysFencedAcrossPromptsUntilExplicitSuccessfulRetry() throws Exception {
        Order order = draft(new BigDecimal("100.00"));
        send("confirm");
        Long menuId = orderItems.findAllByOrderId(order.getId()).get(0).getMenuItem().getId();
        doAnswer(invocation -> {
            assertOutsideTransaction();
            acquireCapacity();
            throw new IllegalStateException("mock revocation response lost");
        }).when(gateway).cancelPaymentLink(LINK.id());

        orders.addItem(order, menuId, 1);
        send("paynow_" + order.getId());
        send("paynow_" + order.getId());

        assertThat(revocationCount(order)).isEqualTo(1);
        assertThat(paymentLink(order)).isEqualTo(LINK.id());
        verify(gateway, times(1)).createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any());

        doAnswer(invocation -> { assertOutsideTransaction(); return null; })
                .when(gateway).cancelPaymentLink(LINK.id());
        revocations.retryPending(); // New worker/pass reads durable rows, not a lost callback.
        assertThat(revocationCount(order)).isZero();
        assertThat(paymentLink(order)).isNull();
        when(gateway.createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any()))
                .thenReturn(new RazorpayService.PaymentLink("plink_after_revoke", "https://example.invalid/new"));
        send("paynow_" + order.getId());
        assertThat(payments.findByOrder(order).orElseThrow().getGatewayLinkId()).isEqualTo("plink_after_revoke");
        verify(gateway, times(2)).createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any());
    }

    @Test
    void sameAmountPaymentPromptCannotReuseAFencedUrl() throws Exception {
        Order order = draft(new BigDecimal("100.00"));
        send("confirm");
        new TransactionTemplate(Objects.requireNonNull(transactions)).executeWithoutResult(status -> {
            orders.revokePaymentLink(order);
        });
        doAnswer(invocation -> { throw new IllegalStateException("mock gateway unavailable"); })
                .when(gateway).cancelPaymentLink(LINK.id());
        clearInvocations(whatsAppClient);

        send("paynow_" + order.getId());

        assertThat(revocationCount(order)).isEqualTo(1);
        verify(whatsAppClient, never()).sendText(anyString(), anyString());
        verify(gateway, times(1)).createPaymentLink(any(), anyString(), anyString(), anyString(), anyString(), anyMap(), any());
    }

    @Test
    void blockingCustomerCancellationAlsoReleasesCapacityBeforeProviderIo() throws Exception {
        Order order = draft(new BigDecimal("100.00"));
        send("confirm");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertOutsideTransaction();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            assertOrderStatus(order.getId(), OrderStatus.CANCELLED);
            assertThat(revocationCount(order)).isEqualTo(1);
            entered.countDown();
            await(release);
            return null;
        }).when(gateway).cancelPaymentLink(LINK.id());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var cancellation = executor.submit(() -> orders.cancelByIdForCustomer(order.getId(), customer.getId()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            executor.submit(this::acquireCapacity).get(3, TimeUnit.SECONDS);
            assertThat(cancellation.isDone()).isFalse();
            release.countDown();
            assertThat(cancellation.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(revocationCount(order)).isZero();
        assertThat(paymentLink(order)).isNull();
    }

    @Test
    void mergeCommitsItemsSourceCancellationAndFenceBeforeRevokingTargetLink() throws Exception {
        Order target = draft(new BigDecimal("100.00"));
        send("confirm");
        Order source = draft(new BigDecimal("100.00"));
        doAnswer(invocation -> {
            assertOutsideTransaction();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            acquireCapacity();
            assertOrderStatus(source.getId(), OrderStatus.CANCELLED);
            assertOrderStatus(target.getId(), OrderStatus.PENDING_CONFIRMATION);
            assertThat(orderItems.sumQuantityByOrderId(target.getId())).isEqualTo(2);
            assertThat(revocationCount(target)).isEqualTo(1);
            return null;
        }).when(gateway).cancelPaymentLink(LINK.id());

        var decision = orders.resolveCart(customer, source, target.getDeliveryDate());

        assertThat(decision.resolution()).isEqualTo(OrderService.CartResolution.MERGED);
        assertThat(decision.order().getId()).isEqualTo(target.getId());
        assertThat(revocationCount(target)).isZero();
        assertThat(paymentLink(target)).isNull();
        verify(gateway).cancelPaymentLink(LINK.id());
    }

    @Test
    void cancellationCommitFailureRollsBackFenceWithoutProviderIo() {
        Order order = draft(new BigDecimal("100.00"));
        send("confirm");
        clearInvocations(gateway);

        assertThatThrownBy(() -> new TransactionTemplate(Objects.requireNonNull(transactions))
                .executeWithoutResult(status -> {
                    orders.cancel(order);
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            throw new IllegalStateException("mock revocation fence commit failure");
                        }
                    });
                })).hasMessage("mock revocation fence commit failure");

        revocations.retryPending();
        assertOrderStatus(order.getId(), OrderStatus.PENDING_CONFIRMATION);
        assertThat(revocationCount(order)).isZero();
        assertThat(paymentLink(order)).isEqualTo(LINK.id());
        verifyNoInteractions(gateway);
    }

    @Test
    void ambiguousCancelCanBeRetriedUsingFreshConfirmedRevokedProviderState() throws Exception {
        Order order = draft(new BigDecimal("100.00"));
        send("confirm");
        doAnswer(invocation -> { throw new IllegalStateException("mock response lost"); })
                .when(gateway).cancelPaymentLink(LINK.id());
        when(gateway.fetchPaymentLink(LINK.id())).thenAnswer(invocation -> {
            assertOutsideTransaction();
            acquireCapacity();
            return new RazorpayService.LinkState("created", BigDecimal.ZERO, null);
        });
        orders.revokePaymentLink(order);
        assertThat(revocationCount(order)).isEqualTo(1);

        when(gateway.fetchPaymentLink(LINK.id())).thenAnswer(invocation -> {
            assertOutsideTransaction();
            acquireCapacity();
            return new RazorpayService.LinkState("cancelled", BigDecimal.ZERO, null);
        });
        revocations.retryPending();

        assertThat(revocationCount(order)).isZero();
        assertThat(paymentLink(order)).isNull();
    }

    private long revocationCount(Order order) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM payment_link_revocations WHERE order_id = ?", Long.class, order.getId()));
    }

    @Test
    void stalePaymentPromptForConfirmedOrderDoesNotCreateOrOverwritePayment() throws Exception {
        Order draft = draft(new BigDecimal("100.00"));
        send("confirm");
        jdbcTemplate.update("UPDATE orders SET status = 'CONFIRMED' WHERE id = ?", draft.getId());
        jdbcTemplate.update("UPDATE payments SET status = 'GATEWAY_CAPTURED' WHERE order_id = ?", draft.getId());
        clearInvocations(gateway, qr);

        send("confirm");

        assertOrderStatus(draft.getId(), OrderStatus.CONFIRMED);
        assertThat(payments.findByOrder(draft).orElseThrow().getStatus()).isEqualTo(PaymentStatus.GATEWAY_CAPTURED);
        verifyNoInteractions(gateway, qr);
        assertThat(sentTexts).anyMatch(text -> text.contains("already confirmed"));
    }

    @Test
    void zeroCreditPaymentChangesStatusBeforePostCommitMessageAndNeverCreatesLink() {
        Order draft = draft(BigDecimal.ZERO);
        doAnswer(invocation -> {
            assertOutsideTransaction();
            acquireCapacity();
            assertOrderStatus(draft.getId(), OrderStatus.PAYMENT_REVIEW_REQUIRED);
            return null;
        }).when(whatsAppClient).sendText(eq(customer.getPhone()), anyString());

        send("confirm");

        assertOrderStatus(draft.getId(), OrderStatus.PAYMENT_REVIEW_REQUIRED);
        assertThat(payments.count()).isZero();
        verify(alerts).raise(eq("CREDIT_COVERED"), anyString(), eq(draft.getId()), eq(customer.getPhone()));
        verifyNoInteractions(gateway, qr);
    }

    private void acquireCapacity() {
        new TransactionTemplate(Objects.requireNonNull(transactions)).executeWithoutResult(status -> capacity.acquire());
    }

    private String dispatchState(Order order) {
        return jdbcTemplate.queryForObject("SELECT state FROM whatsapp_payment_dispatches WHERE order_id = ?",
                String.class, order.getId());
    }

    private void assertReservationCommitted(Order draft) {
        assertOutsideTransaction();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, draft.getId()))
                .isEqualTo("PENDING_CONFIRMATION");
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM whatsapp_conversations WHERE id = ?",
                String.class, conversation.getId())).isEqualTo("PAYMENT_PENDING");
    }

    private static void assertOutsideTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    private String paymentLink(Order order) {
        var payment = payments.findByOrder(order).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getAmount()).isEqualByComparingTo("100.00");
        return payment.getGatewayLinkId();
    }

    private Order draft(BigDecimal total) {
        Order order = new Order();
        order.setCustomer(customer);
        order.setConversation(conversation);
        order.setDeliveryDate(LocalDate.now().plusDays(7).with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)));
        order.setTotalAmount(total);
        order = orderRepository.save(order);
        var menu = itemRepository.findFirstByNameAndActiveTrue("Classic Table White").orElseThrow();
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setMenuItem(menu);
        item.setQuantity(1);
        item.setUnitPrice(menu.getPrice());
        item.setSubtotal(menu.getPrice());
        orderItems.save(item);
        conversation.setState("ORDER_CONFIRM");
        conversation.setContext(new HashMap<>());
        conversation.getContext().put("orderId", order.getId().toString());
        conversation = conversationRepository.save(Objects.requireNonNull(conversation));
        return order;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Mock provider barrier timed out");
    }
}