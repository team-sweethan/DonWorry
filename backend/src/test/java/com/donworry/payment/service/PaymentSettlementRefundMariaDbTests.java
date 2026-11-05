package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.repository.PaymentRepository;
import com.donworry.settlement.api.PaymentRefundSucceeded;
import com.donworry.settlement.entity.Capture;
import com.donworry.settlement.listener.PaymentRefundSucceededListener;
import com.donworry.settlement.repository.CaptureRepository;
import com.donworry.settlement.repository.SettlementRefundAdjustmentRepository;
import com.donworry.settlement.service.SettlementCompletionTransactionService;
import com.donworry.settlement.service.SettlementService;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 발행 어댑터만 테스트에서 등록하고 P5 접수·공개 매입 조회·P6 수신과 지급은 실제 Bean을 사용한다.
@Tag("mariadb")
@SpringBootTest(properties = "settlement.refund-receiving.enabled=true")
@Import({MariaDbTestContainerConfig.class, PaymentSettlementRefundMariaDbTests.DeliveryConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentSettlementRefundMariaDbTests {
    @Autowired
    PaymentCancellationAdmissionService customers;

    @Autowired
    PaymentCancellationQueryService queries;

    @Autowired
    PaymentRepository payments;

    @Autowired
    CaptureRepository captures;

    @Autowired
    SettlementService settlements;

    @Autowired
    SettlementCompletionTransactionService payouts;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    IncompleteEventPublications publications;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    PaymentRefunder refunder;

    @MockitoSpyBean
    SettlementRefundAdjustmentRepository adjustments;

    private Long testMerchant;

    @AfterEach
    void clean() {
        if (testMerchant == null) return;
        jdbc.update(
                "DELETE e FROM EVENT_PUBLICATION e JOIN payments p ON JSON_VALUE(e.SERIALIZED_EVENT,'$.paymentId')=p.payment_id WHERE e.EVENT_TYPE=? AND e.LISTENER_ID=? AND p.merchant_id=?",
                PaymentRefundSucceeded.class.getName(),
                PaymentRefundSucceededListener.LISTENER_ID,
                testMerchant);
        jdbc.update(
                "DELETE a FROM payout_refund_allocations a JOIN merchant_payouts p ON a.payout_id=p.payout_id WHERE p.merchant_id=?",
                testMerchant);
        jdbc.update(
                "DELETE i FROM merchant_payout_items i JOIN merchant_payouts p ON i.payout_id=p.payout_id WHERE p.merchant_id=?",
                testMerchant);
        jdbc.update("DELETE FROM settlement_refund_adjustments WHERE merchant_id=?", testMerchant);
        jdbc.update("DELETE FROM merchant_payouts WHERE merchant_id=?", testMerchant);
        jdbc.update("DELETE FROM settlements WHERE merchant_id=?", testMerchant);
        jdbc.update("DELETE FROM captures WHERE merchant_id=?", testMerchant);
        jdbc.update("DELETE FROM merchant_settlement_locks WHERE merchant_id=?", testMerchant);
        jdbc.update(
                "DELETE c FROM payment_cancellations c JOIN payments p ON c.payment_id=p.payment_id WHERE p.merchant_id=?",
                testMerchant);
        jdbc.update("DELETE FROM payment_cancellation_requests WHERE merchant_id=?", testMerchant);
        jdbc.update("DELETE FROM payments WHERE merchant_id=?", testMerchant);
        jdbc.update("DELETE FROM merchants WHERE merchant_id=?", testMerchant);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DeliveryConfig {
        @Bean
        PaymentRefundCompletion refundIntegrationCompletion(ApplicationEventPublisher events) {
            return events::publishEvent;
        }
    }

    private Payment captured(long merchant, long amount) {
        var at = Instant.now().minus(Duration.ofDays(10)).truncatedTo(ChronoUnit.MICROS);
        return new TransactionTemplate(transactions).execute(status -> {
            var payment = Payment.pending(
                    merchant, "p6-integration", UUID.randomUUID().toString(), amount, at);
            payment.recordAuthorizationSuccess("p6-integration-auth", false);
            payment.completeCapture();
            payment = payments.save(payment);
            var capture = Capture.processing(payment.getPaymentId(), merchant, payment.getOrderRef(), amount, at);
            capture.complete(at);
            capture = captures.saveAndFlush(capture);
            settlements.createSettlement(capture);
            return payment;
        });
    }

    private String pay(long merchant) {
        return payouts.completeMerchant(merchant, Instant.now(), Long.MAX_VALUE);
    }

    private UUID publication(long payment) {
        return UUID.fromString(jdbc.queryForObject(
                "SELECT ID FROM EVENT_PUBLICATION WHERE EVENT_TYPE=? AND LISTENER_ID=? AND JSON_VALUE(SERIALIZED_EVENT,'$.paymentId')=?",
                String.class,
                PaymentRefundSucceeded.class.getName(),
                PaymentRefundSucceededListener.LISTENER_ID,
                payment));
    }

    private void delivered(UUID publication) {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT COMPLETION_DATE IS NOT NULL FROM EVENT_PUBLICATION WHERE ID=?",
                                Boolean.class,
                                publication.toString()))
                        .isTrue());
    }

    // 실제 P5 성공 결과가 P6에 한 번 반영되며 저장 실패 복구에서도 고객 환불을 반복하지 않는다.
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void refund_whenConnectedToSettlement_appliesOnceAndRecoversDelivery(boolean paidBefore, boolean failReceipt) {
        var code = "p6-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO merchants(merchant_code,display_name,status,pg_fee_rate,created_at) VALUES (?,'refund integration','ACTIVE',0.01,UTC_TIMESTAMP(6))",
                code);
        long merchant =
                jdbc.queryForObject("SELECT merchant_id FROM merchants WHERE merchant_code=?", Long.class, code);
        testMerchant = merchant;
        var payment = captured(merchant, 10000);
        var originalCapture = jdbc.queryForMap("SELECT * FROM captures WHERE payment_id=?", payment.getPaymentId());
        if (paidBefore) assertThat(pay(merchant)).isEqualTo("COMPLETED");
        var originalSettlement =
                jdbc.queryForMap("SELECT * FROM settlements WHERE payment_id=?", payment.getPaymentId());
        var originalPayouts = jdbc.queryForList("SELECT * FROM merchant_payouts WHERE merchant_id=?", merchant);
        var command = new PaymentCancellationCommand(
                merchant, payment.getPaymentId(), UUID.randomUUID(), "integration refund");
        if (failReceipt)
            doThrow(new IllegalStateException("test receipt failure"))
                    .when(adjustments)
                    .save(any());
        var result = customers.admit(command);
        assertThat(result.requestStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.amount()).isEqualTo(10000);
        var publication = publication(payment.getPaymentId());
        if (failReceipt) {
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                    "SELECT STATUS FROM EVENT_PUBLICATION WHERE ID=?",
                                    String.class,
                                    publication.toString()))
                            .isEqualTo("FAILED"));
            assertThat(adjustments.findByCancellationId(result.cancellationId()))
                    .isEmpty();
            assertThat(jdbc.queryForMap("SELECT * FROM settlements WHERE payment_id=?", payment.getPaymentId()))
                    .isEqualTo(originalSettlement);
            assertThat(queries.find(merchant, command.requestKey())).isEqualTo(result);
            reset(adjustments);
            publications.resubmitIncompletePublications(p -> p.getIdentifier().equals(publication));
        }
        delivered(publication);
        assertThat(customers.admit(command)).isEqualTo(result);
        var adjustment =
                adjustments.findByCancellationId(result.cancellationId()).orElseThrow();
        assertThat(adjustment.getAdjustmentAmount()).isEqualTo(9700);
        assertThat(adjustment.getRefundAmount()).isEqualTo(10000);
        assertThat(adjustment.getCanceledAt()).isEqualTo(result.completedAt());
        assertThat(adjustment.getDisposition().name()).isEqualTo(paidBefore ? "RECOVERY" : "UNPAID_EXCLUSION");
        if (paidBefore) {
            assertThat(jdbc.queryForMap("SELECT * FROM settlements WHERE payment_id=?", payment.getPaymentId()))
                    .isEqualTo(originalSettlement);
            assertThat(jdbc.queryForList("SELECT * FROM merchant_payouts WHERE merchant_id=?", merchant))
                    .isEqualTo(originalPayouts);
            assertThat(adjustment.getRemainingAmount()).isEqualTo(9700);
            // 다음 지급을 재현하기 위한 격리 DB fixture이며 운영 기록 보정 절차가 아니다.
            jdbc.update(
                    "UPDATE merchant_payouts SET payout_date=DATE_SUB(payout_date, INTERVAL 1 DAY),completed_at=DATE_SUB(completed_at, INTERVAL 1 DAY) WHERE merchant_id=?",
                    merchant);
            jdbc.update(
                    "UPDATE settlements SET completed_at=DATE_SUB(completed_at, INTERVAL 1 DAY) WHERE merchant_id=?",
                    merchant);
            captured(merchant, 20000);
            assertThat(pay(merchant)).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject(
                            "SELECT payout_amount FROM merchant_payouts WHERE merchant_id=? AND payout_date=?",
                            Long.class,
                            merchant,
                            LocalDate.now(ZoneId.of("Asia/Seoul"))))
                    .isEqualTo(9700);
        } else {
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM settlements WHERE payment_id=?", String.class, payment.getPaymentId()))
                    .isEqualTo("CANCELED");
            assertThat(pay(merchant)).isEqualTo("EMPTY");
        }
        assertThat(adjustments
                        .findByCancellationId(result.cancellationId())
                        .orElseThrow()
                        .getRemainingAmount())
                .isZero();
        var captureId =
                captures.findByPaymentId(payment.getPaymentId()).orElseThrow().getCaptureId();
        var duplicate = new PaymentRefundSucceeded(
                result.cancellationId(),
                payment.getPaymentId(),
                captureId,
                merchant,
                result.amount(),
                result.completedAt());
        new TransactionTemplate(transactions).executeWithoutResult(status -> events.publishEvent(duplicate));
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE EVENT_TYPE=? AND LISTENER_ID=? AND JSON_VALUE(SERIALIZED_EVENT,'$.paymentId')=? AND COMPLETION_DATE IS NOT NULL",
                                Long.class,
                                PaymentRefundSucceeded.class.getName(),
                                PaymentRefundSucceededListener.LISTENER_ID,
                                payment.getPaymentId()))
                        .isEqualTo(2));
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM settlement_refund_adjustments WHERE payment_id=?",
                        Long.class,
                        payment.getPaymentId()))
                .isEqualTo(1);
        assertThat(adjustments
                        .findByCancellationId(result.cancellationId())
                        .orElseThrow()
                        .getRemainingAmount())
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payout_refund_allocations WHERE adjustment_id=?",
                        Long.class,
                        adjustment.getAdjustmentId()))
                .isEqualTo(paidBefore ? 1 : 0);
        assertThat(jdbc.queryForMap("SELECT * FROM captures WHERE payment_id=?", payment.getPaymentId()))
                .isEqualTo(originalCapture);
        verify(refunder).refund(new RefundCommand(payment.getPaymentId(), captureId, 10000L));
    }
}
