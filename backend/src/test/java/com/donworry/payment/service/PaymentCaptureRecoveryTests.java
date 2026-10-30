package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.repository.PaymentRepository;
import com.donworry.settlement.api.CaptureCompleted;
import com.donworry.settlement.api.CaptureRequested;
import com.donworry.settlement.repository.SettlementRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class PaymentCaptureRecoveryTests {
    @Autowired
    PaymentEvaluationFlowService flow;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    IncompleteEventPublications publications;

    @MockitoSpyBean
    PaymentCaptureService completion;

    @MockitoSpyBean
    PaymentRepository payments;

    @MockitoSpyBean
    SettlementRepository settlements;

    private PaymentCaptureService completionTarget() {
        // 대역 설정은 트랜잭션 프록시를 호출하지 않고 내부 spy에 적용한다.
        return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(completion);
    }

    private PaymentEvaluationFlowInput input() {
        return new PaymentEvaluationFlowInput(
                2L, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 150_000L);
    }

    private String state(long id) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE payment_id = ?", String.class, id);
    }

    private long count(String table, long id) {
        // 테이블 이름은 아래 테스트의 고정 문자열만 사용한다.
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE payment_id = ?", Long.class, id);
    }

    private UUID publicationId(Class<?> type, long id) {
        return UUID.fromString(jdbc.queryForObject("""
                SELECT ID FROM EVENT_PUBLICATION WHERE EVENT_TYPE = ?
                AND JSON_VALUE(SERIALIZED_EVENT, '$.paymentId') = ?
                """, String.class, type.getName(), id));
    }

    private boolean completed(UUID publicationId) {
        return jdbc.queryForObject(
                "SELECT COMPLETION_DATE IS NOT NULL FROM EVENT_PUBLICATION WHERE ID = ?",
                Boolean.class,
                publicationId.toString());
    }

    private CaptureCompleted completionEvent(long id) {
        return new CaptureCompleted(
                id,
                jdbc.queryForObject("SELECT capture_id FROM captures WHERE payment_id = ?", Long.class, id),
                jdbc.queryForObject(
                                "SELECT captured_at FROM captures WHERE payment_id = ?", java.sql.Timestamp.class, id)
                        .toInstant());
    }

    private void publish(Object event) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> events.publishEvent(event));
    }

    private UUID awaitFailed(Class<?> type, long paymentId) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            UUID id = publicationId(type, paymentId);
            assertThat(jdbc.queryForObject(
                            "SELECT STATUS FROM EVENT_PUBLICATION WHERE ID = ?", String.class, id.toString()))
                    .isEqualTo("FAILED");
            assertThat(completed(id)).isFalse();
        });
        return publicationId(type, paymentId);
    }

    private void resubmit(UUID id) {
        publications.resubmitIncompletePublications(
                publication -> publication.getIdentifier().equals(id));
    }

    // 완료 SQL 반영 후 커밋 실패는 P6 결과를 보존하며 원래 발행 기록 재전달로 복구된다.
    @Test
    void complete_whenCommitFails_resubmitsOriginalPublication() {
        AtomicReference<Thread> listenerThread = new AtomicReference<>();
        Thread caller = Thread.currentThread();
        doAnswer(invocation -> {
                    listenerThread.set(Thread.currentThread());
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                    CaptureCompleted event = invocation.getArgument(0);
                    invocation.callRealMethod();
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            entityManager.flush();
                            assertThat(state(event.paymentId())).isEqualTo("CAPTURED");
                            throw new IllegalStateException("injected completion commit failure");
                        }
                    });
                    return null;
                })
                .when(completionTarget())
                .complete(any());
        var result = flow.createAndAuthorize(input());
        long id = result.paymentId();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(state(id)).isEqualTo("PENDING");
            assertThat(count("captures", id)).isEqualTo(1);
            assertThat(count("settlements", id)).isEqualTo(1);
            assertThat(completed(publicationId(CaptureCompleted.class, id))).isFalse();
        });
        UUID original = awaitFailed(CaptureCompleted.class, id);
        assertThat(completed(publicationId(CaptureRequested.class, id))).isTrue();
        assertThat(listenerThread.get()).isNotSameAs(caller);
        assertThat(jdbc.queryForObject("SELECT authorization_ref FROM payments WHERE payment_id = ?", String.class, id))
                .isEqualTo(result.authorizationRef());
        doCallRealMethod().when(completionTarget()).complete(any());
        resubmit(original);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(state(id)).isEqualTo("CAPTURED");
            assertThat(completed(original)).isTrue();
        });
        assertThat(publicationId(CaptureCompleted.class, id)).isEqualTo(original);
        assertThat(count("captures", id)).isEqualTo(1);
        assertThat(count("settlements", id)).isEqualTo(1);
    }

    // P6 저장 실패는 승인까지 되돌리지 않고 요청 재전달로 매입·정산·완료 반영을 이어간다.
    @Test
    void request_whenSettlementFails_resubmitsWithoutReauthorization() {
        doThrow(new DataIntegrityViolationException("injected settlement failure"))
                .when(settlements)
                .save(any());
        var result = flow.createAndAuthorize(input());
        long id = result.paymentId();
        UUID original = awaitFailed(CaptureRequested.class, id);
        assertThat(state(id)).isEqualTo("PENDING");
        assertThat(count("captures", id)).isZero();
        assertThat(count("settlements", id)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE EVENT_TYPE = ?
                AND JSON_VALUE(SERIALIZED_EVENT, '$.paymentId') = ?
                """, Long.class, CaptureCompleted.class.getName(), id))
                .isZero();
        assertThat(completed(original)).isFalse();
        reset(settlements);
        resubmit(original);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(state(id)).isEqualTo("CAPTURED");
            assertThat(count("captures", id)).isEqualTo(1);
            assertThat(count("settlements", id)).isEqualTo(1);
            assertThat(completed(original)).isTrue();
        });
        assertThat(jdbc.queryForObject("SELECT authorization_ref FROM payments WHERE payment_id = ?", String.class, id))
                .isEqualTo(result.authorizationRef());
        assertThat(count("fds_evaluations", id)).isEqualTo(1);
    }

    // 두 발행 트랜잭션의 동시 완료를 행 잠금으로 직렬화해 상태를 한 번만 전이한다.
    @Test
    void complete_whenConcurrent_serializesStateTransition() throws Exception {
        doThrow(new IllegalStateException("hold initial completion"))
                .when(completionTarget())
                .complete(any());
        var result = flow.createAndAuthorize(input());
        long id = result.paymentId();
        UUID original = awaitFailed(CaptureCompleted.class, id);
        var event = completionEvent(id);
        assertThat(state(id)).isEqualTo("PENDING");
        doCallRealMethod().when(completionTarget()).complete(any());
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondAttempted = new CountDownLatch(1);
        var secondAcquired = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        var pendingSeen = new AtomicInteger();
        doAnswer(invocation -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt == 2) secondAttempted.countDown();
                    Payment payment = entityManager.find(Payment.class, id, LockModeType.PESSIMISTIC_WRITE);
                    if (attempt == 2) secondAcquired.countDown();
                    if (payment.getStatus().name().equals("PENDING")) pendingSeen.incrementAndGet();
                    if (attempt == 1) {
                        acquired.countDown();
                        if (!release.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("test latch timed out");
                    }
                    return java.util.Optional.of(payment);
                })
                .when(payments)
                .findByIdForUpdate(id);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> publish(event));
            assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
            // 독립 전달처럼 값은 같고 객체는 별개인 이벤트를 사용한다.
            var second = executor.submit(
                    () -> publish(new CaptureCompleted(event.paymentId(), event.captureId(), event.capturedAt())));
            assertThat(secondAttempted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(secondAcquired.await(300, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(state(id)).isEqualTo("PENDING");
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE EVENT_TYPE = ?
                        AND JSON_VALUE(SERIALIZED_EVENT, '$.paymentId') = ? AND COMPLETION_DATE IS NOT NULL
                        """, Long.class, CaptureCompleted.class.getName(), id))
                        .isEqualTo(2);
                assertThat(state(id)).isEqualTo("CAPTURED");
            });
            assertThat(pendingSeen.get()).isEqualTo(1);
            assertThat(count("captures", id)).isEqualTo(1);
            assertThat(count("settlements", id)).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        reset(payments);
        resubmit(original);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(completed(original)).isTrue());
        assertThat(jdbc.queryForObject("SELECT authorization_ref FROM payments WHERE payment_id = ?", String.class, id))
                .isEqualTo(result.authorizationRef());
    }
}
