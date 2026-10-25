package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.FdsResult;
import com.donworry.payment.api.PaymentHeld;
import com.donworry.payment.repository.PaymentRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("mariadb")
@SpringBootTest
@Import({MariaDbTestContainerConfig.class, PaymentHeldPersistenceTests.ReceiverConfig.class})
class PaymentHeldPersistenceTests {
    @Autowired
    PaymentEvaluationFlowService flow;

    @Autowired
    PaymentAuthorizationService authorization;

    @Autowired
    PaymentRepository payments;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    @Autowired
    IncompleteEventPublications publications;

    @Autowired
    TestReceiver receiver;

    @MockitoBean
    PaymentAuthorizer authorizer;

    @TestConfiguration(proxyBeanMethods = false)
    static class ReceiverConfig {
        @Bean
        TestReceiver heldTestReceiver(JdbcTemplate jdbc) {
            return new TestReceiver(jdbc);
        }
    }

    // P3의 Case 처리 대신 커밋 후 전달·실패·재전달만 관찰하는 테스트 전용 수신자다.
    public static class TestReceiver {
        private final JdbcTemplate jdbc;
        final Set<Long> failing = ConcurrentHashMap.newKeySet();
        final Map<Long, List<PaymentHeld>> attempts = new ConcurrentHashMap<>();
        final Map<Long, Thread> threads = new ConcurrentHashMap<>();

        TestReceiver(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @ApplicationModuleListener
        public void on(PaymentHeld event) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM payments WHERE payment_id = ?", String.class, event.paymentId()))
                    .isEqualTo("HELD");
            assertThat(jdbc.queryForObject(
                            "SELECT authorization_ref FROM payments WHERE payment_id = ?",
                            String.class,
                            event.paymentId()))
                    .isEqualTo(event.authorizationRef());
            threads.put(event.paymentId(), Thread.currentThread());
            attempts.computeIfAbsent(event.paymentId(), key -> new CopyOnWriteArrayList<>())
                    .add(event);
            if (failing.contains(event.paymentId())) throw new IllegalStateException("injected HELD receiver failure");
        }
    }

    private TestReceiver receiverState() {
        // 리스너 호출은 프록시를 유지하고 관찰용 필드만 실제 대상에서 읽는다.
        return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(receiver);
    }

    private PaymentEvaluationFlowInput input(String customer, long amount) {
        return new PaymentEvaluationFlowInput(999L, customer, UUID.randomUUID().toString(), amount);
    }

    private PaymentAuthorizationContext reviewContext() {
        String customer = UUID.randomUUID().toString();
        flow.createAndEvaluate(input(customer, 50_000L));
        flow.createAndEvaluate(input(customer, 70_000L));
        var evaluated = flow.createAndEvaluate(input(customer, 150_000L));
        assertThat(evaluated.evaluationResult().evaluation().result()).isEqualTo(FdsResult.REVIEW);
        return new PaymentAuthorizationContext(
                PaymentCreationSnapshot.from(
                        payments.findById(evaluated.paymentId()).orElseThrow()),
                evaluated.evaluationResult().evaluation().evaluationRequestId(),
                evaluated.evaluationResult());
    }

    private long publicationCount(long id) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE EVENT_TYPE = ?
                AND JSON_VALUE(SERIALIZED_EVENT, '$.paymentId') = ?
                """, Long.class, PaymentHeld.class.getName(), id);
    }

    private UUID publicationId(long id) {
        return UUID.fromString(jdbc.queryForObject("""
                SELECT ID FROM EVENT_PUBLICATION WHERE EVENT_TYPE = ?
                AND JSON_VALUE(SERIALIZED_EVENT, '$.paymentId') = ?
                """, String.class, PaymentHeld.class.getName(), id));
    }

    private String state(long id) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE payment_id = ?", String.class, id);
    }

    private void awaitCompleted(long id) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(publicationCount(id)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "SELECT COMPLETION_DATE IS NOT NULL FROM EVENT_PUBLICATION WHERE ID = ?",
                            Boolean.class,
                            publicationId(id).toString()))
                    .isTrue();
        });
    }

    // HELD·참조·수신자 발행 기록을 함께 커밋하고 롤백된 통지는 전달하지 않는다.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void authorize_whenTransactionEnds_commitsOrRollsBackHeldAndPublication(boolean rollback) {
        var context = reviewContext();
        long id = context.payment().paymentId();
        var failure = new IllegalStateException("injected HELD before-commit failure");
        when(authorizer.authorize(any())).thenAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    entityManager.flush();
                    assertThat(state(id)).isEqualTo("HELD");
                    assertThat(jdbc.queryForObject(
                                    "SELECT authorization_ref FROM payments WHERE payment_id = ?", String.class, id))
                            .isEqualTo("MOCK-AUTH-" + id);
                    assertThat(publicationCount(id)).isEqualTo(1);
                    assertThat(receiverState().attempts).doesNotContainKey(id);
                    if (rollback) throw failure;
                }
            });
            return new MockPaymentAuthorizer().authorize(invocation.getArgument(0));
        });
        Instant before = Instant.now();
        if (rollback) {
            assertThatThrownBy(() -> authorization.authorize(context)).isSameAs(failure);
            assertThat(state(id)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject(
                            "SELECT authorization_ref FROM payments WHERE payment_id = ?", String.class, id))
                    .isNull();
            assertThat(publicationCount(id)).isZero();
            await().during(Duration.ofMillis(300))
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(receiverState().attempts).doesNotContainKey(id));
        } else {
            var result = authorization.authorize(context);
            Instant after = Instant.now();
            awaitCompleted(id);
            assertThat(result.status().name()).isEqualTo("HELD");
            var event = receiverState().attempts.get(id).getFirst();
            assertThat(event)
                    .isEqualTo(new PaymentHeld(
                            id,
                            999L,
                            context.payment().customerRef(),
                            150_000L,
                            result.authorizationRef(),
                            context.evaluationResult().evaluation().evaluationId(),
                            event.heldAt()));
            assertThat(event.heldAt()).isBetween(before, after);
            assertThat(receiverState().threads.get(id)).isNotSameAs(Thread.currentThread());
            assertThatThrownBy(() -> authorization.authorize(context)).isInstanceOf(IllegalStateException.class);
            assertThat(publicationCount(id)).isEqualTo(1);
            assertThat(receiverState().attempts.get(id)).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fds_evaluations WHERE payment_id = ?", Long.class, id))
                .isEqualTo(1);
        verify(authorizer).authorize(new AuthorizationCommand(id, 150_000L));
    }

    // 수신 실패는 HELD를 되돌리지 않으며 원래 기록을 역직렬화해 동일 값으로 재전달한다.
    @Test
    void receive_whenFailed_resubmitsOriginalPublicationWithoutReauthorization() {
        var context = reviewContext();
        long id = context.payment().paymentId();
        receiverState().failing.add(id);
        when(authorizer.authorize(any()))
                .thenAnswer(invocation -> new MockPaymentAuthorizer().authorize(invocation.getArgument(0)));
        var result = authorization.authorize(context);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT STATUS FROM EVENT_PUBLICATION WHERE ID = ?",
                                String.class,
                                publicationId(id).toString()))
                        .isEqualTo("FAILED"));
        UUID original = publicationId(id);
        assertThat(jdbc.queryForObject(
                        "SELECT COMPLETION_DATE FROM EVENT_PUBLICATION WHERE ID = ?",
                        java.sql.Timestamp.class,
                        original.toString()))
                .isNull();
        assertThat(state(id)).isEqualTo("HELD");
        var first = receiverState().attempts.get(id).getFirst();
        receiverState().failing.remove(id);
        publications.resubmitIncompletePublications(
                publication -> publication.getIdentifier().equals(original));
        awaitCompleted(id);
        assertThat(publicationId(id)).isEqualTo(original);
        assertThat(receiverState().attempts.get(id)).containsExactly(first, first);
        assertThat(receiverState().attempts.get(id).get(1)).isNotSameAs(first);
        assertThat(state(id)).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT authorization_ref FROM payments WHERE payment_id = ?", String.class, id))
                .isEqualTo(result.authorizationRef());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fds_evaluations WHERE payment_id = ?", Long.class, id))
                .isEqualTo(1);
        verify(authorizer).authorize(new AuthorizationCommand(id, 150_000L));
    }

    // 승인 경합에서 첫 커밋 후 두 번째 승인을 거절하여 HELD 통지도 한 건만 남긴다.
    @Test
    void authorize_whenConcurrent_publishesHeldOnce() throws Exception {
        var context = reviewContext();
        long id = context.payment().paymentId();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        when(authorizer.authorize(any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return new MockPaymentAuthorizer().authorize(invocation.getArgument(0));
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> authorization.authorize(context));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondStarted.countDown();
                return authorization.authorize(context);
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).status().name()).isEqualTo("HELD");
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            awaitCompleted(id);
            assertThat(receiverState().attempts.get(id)).hasSize(1);
            verify(authorizer).authorize(new AuthorizationCommand(id, 150_000L));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
