package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class PaymentRequestRealFlowMariaDbTests {
    @Autowired
    PaymentRequestFlowService flow;

    @Autowired
    PaymentRequestAdmissionService admission;

    @Autowired
    PaymentRequestProcessingService processing;

    @Autowired
    PaymentEvaluationService evaluation;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentRequestRepository requests;

    @Autowired
    JdbcTemplate jdbc;

    private PaymentRequestCommand command() {
        return new PaymentRequestCommand(
                999L,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                1000L,
                Instant.now().minusSeconds(1),
                UUID.randomUUID());
    }

    private void completed(long id) {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> assertThat(payments.findById(id).orElseThrow().getStatus())
                                .isEqualTo(PaymentStatus.CAPTURED));
        assertThat(requests.findByPaymentId(id).orElseThrow().getProcessingStage())
                .isEqualTo(PaymentProcessingStage.AUTHORIZED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fds_evaluations WHERE payment_id=?", Long.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT merchant_id FROM fds_evaluations WHERE payment_id=?", Long.class, id))
                .isEqualTo(payments.findById(id).orElseThrow().getMerchantId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM captures WHERE payment_id=?", Long.class, id))
                .isEqualTo(1);
    }

    // P2 커밋 이후 P5 저장 전에 중단돼도 실제 P2의 같은 UUID 응답을 복원한다.
    @Test
    void submit_afterRealP2Commit_restoresEvaluationAndCapturesOnce() {
        var c = command();
        var accepted = admission.admit(c);
        var input = processing.evaluationInput(accepted.paymentId()).orElseThrow();
        var original = evaluation
                .evaluateAndDecide(PaymentRules.State.pending(), input)
                .evaluation();
        flow.submit(c);
        flow.submit(c);
        completed(accepted.paymentId());
        assertThat(requests.findByPaymentId(accepted.paymentId()).orElseThrow().getEvaluationId())
                .isEqualTo(original.evaluationId());
    }

    // 실제 P2·모의 승인·P6 흐름에서도 같은 요청의 동시 실행이 한 결제로 수렴한다.
    @Test
    void submit_whenConcurrentWithRealP2_createsOneEvaluationAndCapture() throws Exception {
        var c = command();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                start.await();
                return flow.submit(c);
            });
            var b = pool.submit(() -> {
                start.await();
                return flow.submit(c);
            });
            start.countDown();
            var first = a.get(20, TimeUnit.SECONDS);
            assertThat(b.get(20, TimeUnit.SECONDS).paymentId()).isEqualTo(first.paymentId());
            completed(first.paymentId());
        }
    }
}
