package com.donworry.settlement.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.settlement.api.CaptureCompleted;
import com.donworry.settlement.api.CaptureRequested;
import com.donworry.settlement.entity.CaptureStatus;
import com.donworry.settlement.entity.SettlementStatus;
import com.donworry.settlement.repository.CaptureRepository;
import com.donworry.settlement.repository.SettlementRepository;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("mariadb")
@SpringBootTest
@Import({MariaDbTestContainerConfig.class, CaptureRequestedIntegrationTests.CompletionConfig.class})
class CaptureRequestedIntegrationTests {
    // P6 단독 테스트의 가상 paymentId를 유지하고 P5 소비자는 별도 통합 테스트에서 검증한다.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.donworry.payment.service.PaymentCaptureService paymentCaptureService;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    CaptureRepository captures;

    @Autowired
    SettlementRepository settlements;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CompletionProbe completions;

    // 발행 측 커밋 전에 매입하지 않고 커밋 후 중복 이벤트도 한 번만 반영한다
    @Test
    void publish_whenCommittedTwice_createsOneCaptureAndSettlement() {
        var event = request(91001L);
        var transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            events.publishEvent(event);
            assertThat(captures.findByPaymentId(event.paymentId())).isEmpty();
        });
        // 재전송은 같은 메모리 객체를 재사용하지 않고 동일 내용의 별도 이벤트로 수신한다.
        transaction.executeWithoutResult(status -> events.publishEvent(request(event.paymentId())));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var capture = captures.findByPaymentId(event.paymentId()).orElseThrow();
            var settlement = settlements.findByPaymentId(event.paymentId()).orElseThrow();
            assertThat(capture.getStatus()).isEqualTo(CaptureStatus.COMPLETED);
            assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.SCHEDULED);
            assertThat(settlement.getCardFeeRate()).isEqualByComparingTo("0.02");
            assertThat(settlement.getPgFeeRate()).isEqualByComparingTo("0.02");
            assertThat(settlement.getSettlementAmount()).isEqualTo(9601L);
            var completed = completions.received().stream()
                    .filter(result -> result.paymentId().equals(event.paymentId()))
                    .toList();
            assertThat(completed).hasSize(1);
            assertThat(completed.getFirst().captureId()).isEqualTo(capture.getCaptureId());
            // DB는 마이크로초 단위이므로 JVM Instant의 나노초 정밀도 차이를 허용한다.
            assertThat(Duration.between(completed.getFirst().capturedAt(), capture.getCapturedAt())
                            .abs())
                    .isLessThan(Duration.ofMillis(1));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM captures WHERE payment_id = ?", Long.class, 91001L))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlements WHERE payment_id = ?", Long.class, 91001L))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM EVENT_PUBLICATION
                    WHERE EVENT_TYPE = ? AND COMPLETION_DATE IS NOT NULL
                    """, Long.class, CaptureRequested.class.getName()))
                    .isGreaterThanOrEqualTo(2);
        });
    }

    // 발행 측이 롤백하면 매입과 이벤트 발행 기록을 남기지 않는다
    @Test
    void publish_whenRolledBack_doesNotCapture() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            events.publishEvent(request(91002L));
            status.setRollbackOnly();
        });
        assertThat(captures.findByPaymentId(91002L)).isEmpty();
        assertThat(settlements.findByPaymentId(91002L)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM EVENT_PUBLICATION
                WHERE EVENT_TYPE = ? AND SERIALIZED_EVENT LIKE '%91002%'
                """, Long.class, CaptureRequested.class.getName()))
                .isZero();
    }

    private CaptureRequested request(long paymentId) {
        return CaptureRequested.builder()
                .paymentId(paymentId)
                .merchantId(2L)
                .orderId("EVENT-" + paymentId)
                .amount(10001L)
                .build();
    }

    @TestConfiguration
    static class CompletionConfig {
        @Bean
        CompletionProbe completionProbe(JdbcTemplate jdbc) {
            return new CompletionProbe(jdbc);
        }
    }

    static class CompletionProbe {
        private final JdbcTemplate jdbc;
        private final CopyOnWriteArrayList<CaptureCompleted> received = new CopyOnWriteArrayList<>();

        CompletionProbe(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        // Spring 프록시의 필드를 직접 읽지 않고 실제 수신 객체의 기록을 조회한다.
        public java.util.List<CaptureCompleted> received() {
            return java.util.List.copyOf(received);
        }

        // P5 대신 커밋된 매입과 정산을 조회해 완료 이벤트의 수신 시점을 확인한다
        @ApplicationModuleListener
        public void on(CaptureCompleted event) {
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM captures c
                    JOIN settlements s ON s.capture_id = c.capture_id
                    WHERE c.capture_id = ? AND c.status = 'COMPLETED' AND s.status = 'SCHEDULED'
                    """, Long.class, event.captureId())).isEqualTo(1);
            received.add(event);
        }
    }
}
