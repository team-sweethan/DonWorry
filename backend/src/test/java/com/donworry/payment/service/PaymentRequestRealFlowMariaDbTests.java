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

    private PaymentRequestCommand command(String customerRef, PaymentRiskSignals signals) {
        return new PaymentRequestCommand(
                999L,
                UUID.randomUUID().toString(),
                customerRef,
                1000L,
                Instant.now().minusSeconds(1),
                UUID.randomUUID(),
                signals);
    }

    private PaymentRiskSignals signals(String country) {
        var id = UUID.randomUUID().toString();
        return PaymentRiskSignals.builder()
                .deviceId("device-" + id)
                .cardToken("card-" + id)
                .cardDisplayLabel("신한 **** 1234")
                .ipCountryCode(country)
                .ipAddress("203.0.113.10")
                .buyerEmail("Buyer-" + id + "@Example.com")
                .build();
    }

    private PaymentStatus settled(long id) {
        await().atMost(Duration.ofSeconds(10))
                .until(
                        () -> payments.findById(id).orElseThrow().getStatus(),
                        status -> status != PaymentStatus.PENDING);
        return payments.findById(id).orElseThrow().getStatus();
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

    // 가맹점이 보낸 FDS 식별자가 시도에 저장되고 실제 P2 평가 기록까지 전달된다 (#325).
    @Test
    void submit_withRiskSignals_passesStoredSignalsToRealFds() {
        var signals = signals("kr");
        var receipt = flow.submit(command(UUID.randomUUID().toString(), signals));
        completed(receipt.paymentId());
        var stored = requests.findByPaymentId(receipt.paymentId()).orElseThrow().riskSignals();
        assertThat(stored.ipCountryCode()).isEqualTo("KR");
        assertThat(stored.buyerEmail()).isEqualTo(signals.buyerEmail()).isLowerCase();
        var fds = jdbc.queryForMap("""
                SELECT device_id, card_token, card_display_label, ip_country_code, ip_address, buyer_email
                FROM fds_evaluations WHERE payment_id=?
                """, receipt.paymentId());
        assertThat(fds)
                .containsEntry("device_id", stored.deviceId())
                .containsEntry("card_token", stored.cardToken())
                .containsEntry("card_display_label", stored.cardDisplayLabel())
                .containsEntry("ip_country_code", "KR")
                .containsEntry("ip_address", stored.ipAddress())
                .containsEntry("buyer_email", stored.buyerEmail());
    }

    // 차단 목록(R07)에 있는 카드 토큰으로 결제하면 실제 P2가 DENY하고 결제는 거절로 끝난다.
    @Test
    void submit_whenCardTokenBlocklisted_isDeniedByRealFds() {
        var signals = signals(null);
        jdbc.update("""
                INSERT INTO fds_blocklist_entries
                    (identifier_type, identifier_value, reason, active, created_at, expires_at)
                VALUES ('CARD_TOKEN', ?, 'payment test', TRUE, UTC_TIMESTAMP(6), NULL)
                """, signals.cardToken());
        var receipt = flow.submit(command(UUID.randomUUID().toString(), signals));
        assertThat(settled(receipt.paymentId())).isEqualTo(PaymentStatus.DENIED);
        assertThat(jdbc.queryForObject(
                        "SELECT result FROM fds_evaluations WHERE payment_id=?", String.class, receipt.paymentId()))
                .isEqualTo("DENY");
    }

    // 승인 이력이 있는 구매자가 새 단말기 · 새 카드 · 고위험 국가 IP로 결제하면 25+25+40점으로 DENY다.
    @Test
    void submit_withNewDeviceCardAndHighRiskCountry_isDeniedByRealFds() {
        var customer = UUID.randomUUID().toString();
        var approved = flow.submit(command(customer, signals(null)));
        completed(approved.paymentId());
        jdbc.update(
                "INSERT IGNORE INTO fds_high_risk_countries (country_code, active, created_at) VALUES ('XQ', TRUE, UTC_TIMESTAMP(6))");

        var risky = flow.submit(command(customer, signals("XQ")));
        assertThat(settled(risky.paymentId())).isEqualTo(PaymentStatus.DENIED);
        assertThat(jdbc.queryForMap(
                        "SELECT result, risk_score FROM fds_evaluations WHERE payment_id=?", risky.paymentId()))
                .containsEntry("result", "DENY")
                .containsEntry("risk_score", 90);
    }

    // 식별자를 보내지 않으면 지금처럼 비워 두고 해당 룰만 적중하지 않는다.
    @Test
    void submit_withoutRiskSignals_keepsSignalsEmpty() {
        var receipt = flow.submit(command());
        completed(receipt.paymentId());
        assertThat(requests.findByPaymentId(receipt.paymentId()).orElseThrow().riskSignals())
                .isEqualTo(PaymentRiskSignals.none());
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM fds_evaluations WHERE payment_id=? AND device_id IS NULL"
                                + " AND card_token IS NULL AND buyer_email IS NULL",
                        Long.class,
                        receipt.paymentId()))
                .isEqualTo(1);
    }
}
