package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.payment.api.*;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.repository.*;
import com.donworry.settlement.api.*;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("mariadb")
@SpringBootTest
@Import({MariaDbTestContainerConfig.class, PaymentActionVoidMariaDbTests.ReceiverConfig.class})
class PaymentActionVoidMariaDbTests {
    private static final String TEST_RECEIVER_LISTENER_ID =
            ResultReceiver.class.getName() + ".on(" + PaymentActionResult.class.getName() + ")";

    @Autowired
    PaymentActionRequester requester;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentActionRepository actions;

    @Autowired
    PaymentCaptureService completion;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager em;

    @Autowired
    IncompleteEventPublications publications;

    @Autowired
    ResultReceiver receiver;

    @MockitoSpyBean
    PaymentVoider voider;

    @TestConfiguration(proxyBeanMethods = false)
    static class ReceiverConfig {
        @Bean
        ResultReceiver voidResultTestReceiver(JdbcTemplate jdbc) {
            return new ResultReceiver(jdbc);
        }
    }

    public static class ResultReceiver {
        private final JdbcTemplate jdbc;
        final Map<Long, List<PaymentActionResult>> received = new ConcurrentHashMap<>();
        final Set<Long> failing = ConcurrentHashMap.newKeySet();

        ResultReceiver(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }
        // P3 대신 최종 상태 커밋 이후 결과가 도착했는지 확인한다.
        @ApplicationModuleListener
        public void on(PaymentActionResult result) {
            boolean success = "ACCEPTED".equals(result.status());
            assertThat(result.actionType()).isEqualTo("VOID");
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM payments WHERE payment_id=?", String.class, result.paymentId()))
                    .isEqualTo(success ? "VOIDED" : "HELD");
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM payment_actions WHERE payment_id=?", String.class, result.paymentId()))
                    .isEqualTo(success ? "SUCCEEDED" : "FAILED");
            received.computeIfAbsent(result.paymentId(), id -> new CopyOnWriteArrayList<>())
                    .add(result);
            if (failing.contains(result.paymentId())) throw new IllegalStateException("injected VOID result failure");
        }
    }

    private ResultReceiver state() {
        return AopTestUtils.getUltimateTargetObject(receiver);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(manager);
    }

    private PaymentActionCommand command() {
        long id = tx().execute(s -> {
            var p = Payment.pending(999L, "void-test", UUID.randomUUID().toString(), 123456L, Instant.now());
            p.recordAuthorizationSuccess("stored-void-auth", true);
            return payments.save(p).getPaymentId();
        });
        return new PaymentActionCommand(id, id, "VOID", UUID.randomUUID());
    }

    private long count(String table, long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE payment_id=?", Long.class, id);
    }

    // 결과 이벤트는 P3 수신 기록을 제외하고 테스트 수신자에 대한 발행만 확인한다.
    private long eventCount(Class<?> type, long id) {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM EVENT_PUBLICATION
                WHERE EVENT_TYPE=? AND JSON_VALUE(SERIALIZED_EVENT, '$.paymentId')=?
                  AND (EVENT_TYPE<>? OR LISTENER_ID=?)
                """, Long.class, type.getName(), id, PaymentActionResult.class.getName(), TEST_RECEIVER_LISTENER_ID);
    }

    private UUID publication(long id) {
        return UUID.fromString(jdbc.queryForObject(
                """
                SELECT ID FROM EVENT_PUBLICATION
                WHERE EVENT_TYPE=? AND JSON_VALUE(SERIALIZED_EVENT, '$.paymentId')=?
                  AND LISTENER_ID=?
                """, String.class, PaymentActionResult.class.getName(), id, TEST_RECEIVER_LISTENER_ID));
    }

    private void delivered(long id, int attempts) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(state().received.get(id)).hasSize(attempts);
            assertThat(jdbc.queryForObject(
                            "SELECT COMPLETION_DATE IS NOT NULL FROM EVENT_PUBLICATION WHERE ID=?",
                            Boolean.class,
                            publication(id).toString()))
                    .isTrue();
        });
    }

    // 성공 후 재요청은 저장한 actionId를 반환하며 같은 승인을 다시 취소하지 않는다.
    @Test
    void request_whenVoidSucceeds_returnsActionIdAndPreservesAuthorization() {
        var c = command();
        var result = requester.request(c);
        delivered(c.paymentId(), 1);
        var action = actions.findByPaymentId(c.paymentId()).orElseThrow();
        assertThat(result).isEqualTo(PaymentActionReceipt.accepted(action.getActionId()));
        assertThat(requester.request(c)).isEqualTo(result);
        assertThat(state().received.get(c.paymentId()).getFirst())
                .isEqualTo(new PaymentActionResult(
                        c.paymentId(),
                        c.decisionId(),
                        "VOID",
                        c.idempotencyKey(),
                        "ACCEPTED",
                        action.getActionId(),
                        null,
                        action.getCompletedAt()));
        assertThat(action.getCaptureId()).isNull();
        assertThat(payments.findById(c.paymentId()).orElseThrow().getAuthorizationRef())
                .isEqualTo("stored-void-auth");
        assertThat(count("captures", c.paymentId())).isZero();
        assertThat(count("settlements", c.paymentId())).isZero();
        assertThat(eventCount(CaptureRequested.class, c.paymentId())).isZero();
        assertThat(eventCount(PaymentActionResult.class, c.paymentId())).isEqualTo(1);
        verify(voider).voidAuthorization(new AuthorizationVoidCommand(c.paymentId(), "stored-void-auth"));
    }

    // 업무 거절은 HELD를 유지하며 재시도에도 같은 실패 결과를 반환한다.
    @Test
    void request_whenDeclined_persistsFailureWithoutVoiding() {
        var c = command();
        doReturn(AuthorizationVoidResult.DECLINED).when(voider).voidAuthorization(any());
        var result = requester.request(c);
        delivered(c.paymentId(), 1);
        assertThat(result).isEqualTo(PaymentActionReceipt.rejected("VOID_DECLINED"));
        assertThat(requester.request(c)).isEqualTo(result);
        var action = actions.findByPaymentId(c.paymentId()).orElseThrow();
        assertThat(action.getStatus().name()).isEqualTo("FAILED");
        assertThat(action.getCompletedAt()).isNotNull();
        assertThat(state().received.get(c.paymentId()).getFirst().targetOperationId())
                .isNull();
        assertThat(payments.findById(c.paymentId()).orElseThrow().getStatus().name())
                .isEqualTo("HELD");
        verify(voider).voidAuthorization(any());
    }

    // 기술 예외와 응답 없음은 요청·결과를 남기지 않으며 같은 키로 다시 시도할 수 있다.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void request_whenTechnicalFailure_rollsBackAndCanRetry(boolean noResponse) {
        var c = command();
        if (noResponse) doReturn(null).when(voider).voidAuthorization(any());
        else
            doThrow(new IllegalStateException("injected technical failure"))
                    .when(voider)
                    .voidAuthorization(any());
        assertThatThrownBy(() -> requester.request(c)).isInstanceOf(IllegalStateException.class);
        assertThat(count("payment_actions", c.paymentId())).isZero();
        assertThat(eventCount(PaymentActionResult.class, c.paymentId())).isZero();
        assertThat(payments.findById(c.paymentId()).orElseThrow().getStatus().name())
                .isEqualTo("HELD");
        doCallRealMethod().when(voider).voidAuthorization(any());
        assertThat(requester.request(c).status()).isEqualTo("ACCEPTED");
        delivered(c.paymentId(), 1);
        verify(voider, times(2)).voidAuthorization(any());
    }

    // 커밋 직전 실패하면 VOIDED·조치 완료·결과 발행 기록을 모두 되돌린다.
    @Test
    void request_whenCommitFails_rollsBackAllChanges() {
        var c = command();
        doAnswer(inv -> {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            em.flush();
                            assertThat(jdbc.queryForObject(
                                            "SELECT status FROM payments WHERE payment_id=?",
                                            String.class,
                                            c.paymentId()))
                                    .isEqualTo("VOIDED");
                            assertThat(eventCount(PaymentActionResult.class, c.paymentId()))
                                    .isEqualTo(1);
                            assertThat(state().received).doesNotContainKey(c.paymentId());
                            throw new IllegalStateException("injected VOID commit failure");
                        }
                    });
                    return inv.callRealMethod();
                })
                .when(voider)
                .voidAuthorization(any());
        assertThatThrownBy(() -> requester.request(c)).isInstanceOf(IllegalStateException.class);
        assertThat(count("payment_actions", c.paymentId())).isZero();
        assertThat(eventCount(PaymentActionResult.class, c.paymentId())).isZero();
        assertThat(payments.findById(c.paymentId()).orElseThrow().getStatus().name())
                .isEqualTo("HELD");
        assertThat(state().received).doesNotContainKey(c.paymentId());
    }

    // 최종 결과의 수신 실패를 복구할 때 모의 취소를 재실행하지 않는다.
    @Test
    void result_whenReceiverFails_resubmitsWithoutVoidingAgain() {
        var c = command();
        state().failing.add(c.paymentId());
        var first = requester.request(c);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT STATUS FROM EVENT_PUBLICATION WHERE ID=?",
                                String.class,
                                publication(c.paymentId()).toString()))
                        .isEqualTo("FAILED"));
        UUID original = publication(c.paymentId());
        assertThat(requester.request(c)).isEqualTo(first);
        state().failing.remove(c.paymentId());
        publications.resubmitIncompletePublications(p -> p.getIdentifier().equals(original));
        delivered(c.paymentId(), 2);
        assertThat(state().received.get(c.paymentId()).get(0))
                .isEqualTo(state().received.get(c.paymentId()).get(1));
        verify(voider).voidAuthorization(any());
    }

    // 취소 중 동일/반대 요청이 도착해도 첫 커밋까지 기다린 후 기존 결과 또는 충돌을 반환한다.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void request_whenConcurrent_serializesRepeatAndOpposite(boolean repeat) throws Exception {
        var c = command();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(inv -> {
                    entered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                    return inv.callRealMethod();
                })
                .when(voider)
                .voidAuthorization(any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> requester.request(c));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var secondCommand =
                    repeat ? c : new PaymentActionCommand(c.paymentId(), c.decisionId(), "CAPTURE", UUID.randomUUID());
            var secondStarted = new CountDownLatch(1);
            var second = pool.submit(() -> {
                secondStarted.countDown();
                return requester.request(secondCommand);
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            var result = first.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS))
                    .isEqualTo(repeat ? result : PaymentActionReceipt.rejected("PAYMENT_ACTION_CONFLICT"));
            delivered(c.paymentId(), 1);
            assertThat(count("payment_actions", c.paymentId())).isEqualTo(1);
            assertThat(eventCount(CaptureRequested.class, c.paymentId())).isZero();
            verify(voider).voidAuthorization(any());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    // VOID 후 도착한 매입 완료 이벤트는 취소 결과를 덮어쓰지 않는다.
    @Test
    void complete_whenVoidAlreadyChosen_rejectsCaptureResult() {
        var c = command();
        requester.request(c);
        delivered(c.paymentId(), 1);
        assertThatThrownBy(() -> tx().executeWithoutResult(
                                s -> completion.complete(new CaptureCompleted(c.paymentId(), 999L, Instant.now()))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(payments.findById(c.paymentId()).orElseThrow().getStatus().name())
                .isEqualTo("VOIDED");
        assertThat(actions.findByPaymentId(c.paymentId()).orElseThrow().getCaptureId())
                .isNull();
        assertThat(eventCount(PaymentActionResult.class, c.paymentId())).isEqualTo(1);
    }
}
