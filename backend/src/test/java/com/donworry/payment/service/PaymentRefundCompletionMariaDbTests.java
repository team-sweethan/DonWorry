package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.repository.PaymentRepository;
import com.donworry.settlement.api.PaymentRefundSucceeded;
import com.donworry.settlement.entity.Capture;
import com.donworry.settlement.listener.PaymentRefundSucceededListener;
import com.donworry.settlement.repository.CaptureRepository;
import com.donworry.settlement.repository.SettlementRefundAdjustmentRepository;
import com.donworry.settlement.service.SettlementService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 테스트 대역 없이 운영 발행 어댑터와 재전달 스케줄러를 켜고 P6 수신까지 확인한다 (#383).
// 스케줄러가 저절로 돌지 않게 간격을 길게 두고 run()을 직접 부른다.
@Tag("mariadb")
@SpringBootTest(
        properties = {
            "settlement.refund-receiving.enabled=true",
            "payment.refund-completion.enabled=true",
            "payment.refund-completion.redelivery-delay=PT1H"
        })
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentRefundCompletionMariaDbTests {
    @Autowired
    PaymentCancellationAdmissionService customers;

    @Autowired
    PaymentRefundRedeliveryScheduler redelivery;

    @Autowired
    PaymentRepository payments;

    @Autowired
    CaptureRepository captures;

    @Autowired
    SettlementService settlements;

    @Autowired
    PlatformTransactionManager transactions;

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
                "DELETE e FROM EVENT_PUBLICATION e JOIN payments p ON JSON_VALUE(e.SERIALIZED_EVENT,'$.paymentId')=p.payment_id WHERE e.EVENT_TYPE=? AND p.merchant_id=?",
                PaymentRefundSucceeded.class.getName(),
                testMerchant);
        jdbc.update("DELETE FROM settlement_refund_adjustments WHERE merchant_id=?", testMerchant);
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

    // 플래그를 켜면 CAPTURED 고객 취소가 503 없이 환불되고 P6 정산에서 지급 제외된다
    @Test
    void admit_whenCompletionEnabled_refundsAndReachesSettlement() {
        long merchant = merchant();
        var payment = captured(merchant, 10000);

        var result = customers.admit(command(merchant, payment));

        assertThat(result.requestStatus()).isEqualTo("SUCCEEDED");
        delivered(publication(payment.getPaymentId()));
        var adjustment =
                adjustments.findByCancellationId(result.cancellationId()).orElseThrow();
        assertThat(adjustment.getRefundAmount()).isEqualTo(10000);
        assertThat(adjustment.getDisposition().name()).isEqualTo("UNPAID_EXCLUSION");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM settlements WHERE payment_id=?", String.class, payment.getPaymentId()))
                .isEqualTo("CANCELED");
    }

    // P6 저장이 실패해 FAILED로 남은 발행은 스케줄러가 다시 보내고, 고객 환불은 한 번만 실행한다
    @Test
    void run_whenReceiptFailed_redeliversWithoutRefundingAgain() {
        long merchant = merchant();
        var payment = captured(merchant, 10000);
        doThrow(new IllegalStateException("test receipt failure"))
                .when(adjustments)
                .save(any());

        var result = customers.admit(command(merchant, payment));

        assertThat(result.requestStatus()).isEqualTo("SUCCEEDED");
        var publication = publication(payment.getPaymentId());
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT STATUS FROM EVENT_PUBLICATION WHERE ID=?",
                                String.class,
                                publication.toString()))
                        .isEqualTo("FAILED"));
        // 재전달 상한은 실패마다 시도 횟수가 쌓이는 것에 기대므로 실제 저장값을 확인한다
        assertThat(jdbc.queryForObject(
                        "SELECT COMPLETION_ATTEMPTS FROM EVENT_PUBLICATION WHERE ID=?",
                        Integer.class,
                        publication.toString()))
                .isEqualTo(1);
        assertThat(adjustments.findByCancellationId(result.cancellationId())).isEmpty();
        reset(adjustments);

        redelivery.run();

        delivered(publication);
        assertThat(adjustments.findByCancellationId(result.cancellationId())).isPresent();
        verify(refunder, times(1)).refund(any());
    }

    private long merchant() {
        var code = "p5-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO merchants(merchant_code,display_name,status,pg_fee_rate,created_at) VALUES (?,'refund completion','ACTIVE',0.01,UTC_TIMESTAMP(6))",
                code);
        testMerchant = jdbc.queryForObject("SELECT merchant_id FROM merchants WHERE merchant_code=?", Long.class, code);
        return testMerchant;
    }

    private PaymentCancellationCommand command(long merchant, Payment payment) {
        return new PaymentCancellationCommand(merchant, payment.getPaymentId(), UUID.randomUUID(), "refund completion");
    }

    private Payment captured(long merchant, long amount) {
        var at = Instant.now().minus(Duration.ofDays(10)).truncatedTo(ChronoUnit.MICROS);
        return new TransactionTemplate(transactions).execute(status -> {
            var payment =
                    Payment.pending(merchant, "p5-completion", UUID.randomUUID().toString(), amount, at);
            payment.recordAuthorizationSuccess("p5-completion-auth", false);
            payment.completeCapture();
            payment = payments.save(payment);
            var capture = Capture.processing(payment.getPaymentId(), merchant, payment.getOrderRef(), amount, at);
            capture.complete(at);
            capture = captures.saveAndFlush(capture);
            settlements.createSettlement(capture);
            return payment;
        });
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
}
