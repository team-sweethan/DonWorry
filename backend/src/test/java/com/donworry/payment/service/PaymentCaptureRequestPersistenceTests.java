package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.settlement.api.CaptureCompleted;
import com.donworry.settlement.api.CaptureRequested;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class PaymentCaptureRequestPersistenceTests {
    @Autowired
    PaymentCaptureService captureService;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactionManager;

    // 서비스 직접 호출도 트랜잭션 없이 상태를 바꿀 수 없다.
    @Test
    void complete_whenNoTransaction_rejectsDirectInvocation() {
        assertThatThrownBy(() -> captureService.complete(new CaptureCompleted(1L, 2L, java.time.Instant.now())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Autowired
    PaymentEvaluationFlowService flow;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    @MockitoBean
    PaymentAuthorizer authorizer;

    private long requestCount(long paymentId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM EVENT_PUBLICATION
                WHERE EVENT_TYPE = ? AND JSON_VALUE(SERIALIZED_EVENT, '$.paymentId') = ?
                """, Long.class, CaptureRequested.class.getName(), paymentId);
    }

    private long captureCount(long paymentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM captures WHERE payment_id = ?", Long.class, paymentId);
    }

    // 승인 참조와 요청 기록이 같은 트랜잭션에 있으며 롤백된 요청은 실제 P6에 전달되지 않는다.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createAndAuthorize_whenTransactionEnds_commitsOrRollsBackRequestWithAuthorization(boolean rollback) {
        var id = new AtomicLong();
        var failure = new IllegalStateException("injected before-commit failure");
        when(authorizer.authorize(any())).thenAnswer(invocation -> {
            AuthorizationCommand command = invocation.getArgument(0);
            id.set(command.paymentId());
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    entityManager.flush();
                    assertThat(jdbc.queryForObject(
                                    "SELECT authorization_ref FROM payments WHERE payment_id = ?",
                                    String.class,
                                    id.get()))
                            .isEqualTo("MOCK-AUTH-" + id.get());
                    assertThat(requestCount(id.get())).isEqualTo(1);
                    assertThat(captureCount(id.get())).isZero();
                    if (rollback) throw failure;
                }
            });
            return new MockPaymentAuthorizer().authorize(command);
        });
        String order = UUID.randomUUID().toString();
        var input = new PaymentEvaluationFlowInput(999L, UUID.randomUUID().toString(), order, 150_000L);
        if (rollback) {
            assertThatThrownBy(() -> flow.createAndAuthorize(input)).isSameAs(failure);
            assertThat(jdbc.queryForObject(
                            "SELECT authorization_ref FROM payments WHERE payment_id = ?", String.class, id.get()))
                    .isNull();
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE payment_id = ?", String.class, id.get()))
                    .isEqualTo("PENDING");
            assertThat(requestCount(id.get())).isZero();
            assertThat(captureCount(id.get())).isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM settlements WHERE payment_id = ?", Long.class, id.get()))
                    .isZero();
        } else {
            var result = flow.createAndAuthorize(input);
            assertThat(result.authorizationRef()).isEqualTo("MOCK-AUTH-" + id.get());
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(captureCount(id.get())).isEqualTo(1);
                assertThat(jdbc.queryForObject(
                                "SELECT status FROM payments WHERE payment_id = ?", String.class, id.get()))
                        .isEqualTo("CAPTURED");
                assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE EVENT_TYPE = ?
                        AND JSON_VALUE(SERIALIZED_EVENT, '$.paymentId') = ? AND COMPLETION_DATE IS NOT NULL
                        """, Long.class, CaptureCompleted.class.getName(), id.get()))
                        .isEqualTo(1);

                assertThat(jdbc.queryForObject(
                                "SELECT COUNT(*) FROM captures WHERE payment_id = ? AND merchant_id = ? AND order_id = ? AND capture_amount = ? AND status = 'COMPLETED'",
                                Long.class,
                                id.get(),
                                999L,
                                order,
                                150_000L))
                        .isEqualTo(1);
                assertThat(jdbc.queryForObject(
                                "SELECT COUNT(*) FROM settlements WHERE payment_id = ? AND status = 'SCHEDULED'",
                                Long.class,
                                id.get()))
                        .isEqualTo(1);
                assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM EVENT_PUBLICATION
                        WHERE EVENT_TYPE = ? AND JSON_VALUE(SERIALIZED_EVENT, '$.paymentId') = ?
                        AND COMPLETION_DATE IS NOT NULL
                        """, Long.class, CaptureRequested.class.getName(), id.get()))
                        .isEqualTo(1);
            });
        }
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM fds_evaluations WHERE payment_id = ?", Long.class, id.get()))
                .isEqualTo(1);
        verify(authorizer).authorize(new AuthorizationCommand(id.get(), 150_000L));
    }
    // 실제 완료 이벤트를 재발행해도 승인 참조를 덮어쓰지 않고 정상 수신 처리한다.
    @Test
    void complete_whenDuplicateEvent_preservesStateAndCompletesPublication() {
        when(authorizer.authorize(any()))
                .thenAnswer(invocation -> new MockPaymentAuthorizer().authorize(invocation.getArgument(0)));
        var result = flow.createAndAuthorize(new PaymentEvaluationFlowInput(
                999L, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 150_000L));
        long id = result.paymentId();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT status FROM payments WHERE payment_id = ?", String.class, id))
                        .isEqualTo("CAPTURED"));
        long captureId = jdbc.queryForObject("SELECT capture_id FROM captures WHERE payment_id = ?", Long.class, id);
        var capturedAt = jdbc.queryForObject(
                        "SELECT captured_at FROM captures WHERE payment_id = ?", java.sql.Timestamp.class, id)
                .toInstant();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> events.publishEvent(new CaptureCompleted(id, captureId, capturedAt)));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE EVENT_TYPE = ?
                    AND JSON_VALUE(SERIALIZED_EVENT, '$.paymentId') = ? AND COMPLETION_DATE IS NOT NULL
                    """, Long.class, CaptureCompleted.class.getName(), id))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject(
                            "SELECT authorization_ref FROM payments WHERE payment_id = ?", String.class, id))
                    .isEqualTo(result.authorizationRef());
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE payment_id = ?", String.class, id))
                    .isEqualTo("CAPTURED");
        });
    }
}
