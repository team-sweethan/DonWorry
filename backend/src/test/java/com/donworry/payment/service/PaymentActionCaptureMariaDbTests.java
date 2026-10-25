package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.payment.api.*;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.entity.PaymentAction;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

// 전용 수신자·대역이 있는 컨텍스트를 끝나면 닫아 공유 MariaDB의 연결 한도를 넘지 않는다.
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("mariadb")
@SpringBootTest
@Import({MariaDbTestContainerConfig.class, PaymentActionCaptureMariaDbTests.ReceiverConfig.class})
class PaymentActionCaptureMariaDbTests {
    @Autowired
    PaymentActionRequester requester;

    @Autowired
    PaymentRepository payments;

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
    PaymentActionRepository actions;

    @MockitoSpyBean
    PaymentCaptureService completion;

    @MockitoSpyBean
    PaymentActionAdmissionService admission;

    @TestConfiguration(proxyBeanMethods = false)
    static class ReceiverConfig {
        @Bean
        ResultReceiver actionResultTestReceiver(JdbcTemplate jdbc) {
            return new ResultReceiver(jdbc);
        }
    }

    // P3 대신 커밋된 결제·조치와 전달 내용을 확인하는 테스트 전용 수신자다.
    public static class ResultReceiver {
        private final JdbcTemplate jdbc;
        final Map<Long, List<PaymentActionResult>> received = new ConcurrentHashMap<>();
        final Set<Long> failing = ConcurrentHashMap.newKeySet();

        ResultReceiver(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @ApplicationModuleListener
        public void on(PaymentActionResult result) {
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM payments WHERE payment_id=?", String.class, result.paymentId()))
                    .isEqualTo("CAPTURED");
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM payment_actions WHERE payment_id=?", String.class, result.paymentId()))
                    .isEqualTo("SUCCEEDED");
            received.computeIfAbsent(result.paymentId(), id -> new CopyOnWriteArrayList<>())
                    .add(result);
            if (failing.contains(result.paymentId())) throw new IllegalStateException("injected result failure");
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
            var p = Payment.pending(999L, "action-capture", UUID.randomUUID().toString(), 123456L, Instant.now());
            p.recordAuthorizationSuccess("held-auth", true);
            return payments.save(p).getPaymentId();
        });
        return new PaymentActionCommand(id, id, "CAPTURE", UUID.randomUUID());
    }

    private long count(String table, long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE payment_id=?", Long.class, id);
    }

    private long eventCount(Class<?> type, long id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE EVENT_TYPE=? AND JSON_VALUE(SERIALIZED_EVENT, '$.paymentId')=?",
                Long.class,
                type.getName(),
                id);
    }

    private UUID publication(Class<?> type, long id) {
        return UUID.fromString(jdbc.queryForObject(
                "SELECT ID FROM EVENT_PUBLICATION WHERE EVENT_TYPE=? AND JSON_VALUE(SERIALIZED_EVENT, '$.paymentId')=?",
                String.class,
                type.getName(),
                id));
    }

    private String publicationStatus(UUID id) {
        return jdbc.queryForObject("SELECT STATUS FROM EVENT_PUBLICATION WHERE ID=?", String.class, id.toString());
    }

    private void completed(long id) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(state().received.get(id)).hasSize(1);
            assertThat(jdbc.queryForObject(
                            "SELECT COMPLETION_DATE IS NOT NULL FROM EVENT_PUBLICATION WHERE ID=?",
                            Boolean.class,
                            publication(PaymentActionResult.class, id).toString()))
                    .isTrue();
        });
    }

    private void resubmit(UUID id) {
        publications.resubmitIncompletePublications(p -> p.getIdentifier().equals(id));
    }

    // 실제 P6가 저장한 매입·정산 결과로 직원 결제를 완료하고 원래 판정 정보를 전달한다.
    @Test
    void request_whenCapture_completesAndReplaysStoredResult() {
        var c = command();
        assertThat(requester.request(c)).isEqualTo(PaymentActionReceipt.pending());
        completed(c.paymentId());
        var action = actions.findByPaymentId(c.paymentId()).orElseThrow();
        assertThat(requester.request(c)).isEqualTo(PaymentActionReceipt.accepted(action.getCaptureId()));
        var result = state().received.get(c.paymentId()).getFirst();
        assertThat(result)
                .isEqualTo(new PaymentActionResult(
                        c.paymentId(),
                        c.decisionId(),
                        "CAPTURE",
                        c.idempotencyKey(),
                        "ACCEPTED",
                        action.getCaptureId(),
                        null,
                        action.getCompletedAt()));
        assertThat(count("captures", c.paymentId())).isEqualTo(1);
        assertThat(count("settlements", c.paymentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT capture_amount FROM captures WHERE payment_id=?", Long.class, c.paymentId()))
                .isEqualTo(123456L);
        assertThat(jdbc.queryForObject("SELECT order_id FROM captures WHERE payment_id=?", String.class, c.paymentId()))
                .isEqualTo(payments.findById(c.paymentId()).orElseThrow().getOrderRef());
        tx().executeWithoutResult(s -> completion.complete(
                new CaptureCompleted(c.paymentId(), action.getCaptureId(), action.getCompletedAt())));
        assertThat(eventCount(CaptureRequested.class, c.paymentId())).isEqualTo(1);
        assertThat(eventCount(PaymentActionResult.class, c.paymentId())).isEqualTo(1);
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> completion.complete(
                        new CaptureCompleted(c.paymentId(), action.getCaptureId() + 1, action.getCompletedAt()))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(actions.findByPaymentId(c.paymentId()).orElseThrow().getCaptureId())
                .isEqualTo(action.getCaptureId());
    }

    // 요청 커밋 실패 시 조치와 매입 발행 기록을 함께 롤백한다.
    @Test
    void request_whenCommitFails_rollsBackActionAndPublication() {
        var c = command();
        doAnswer(inv -> {
                    Object saved = inv.callRealMethod();
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            em.flush();
                            assertThat(eventCount(CaptureRequested.class, c.paymentId()))
                                    .isEqualTo(1);
                            throw new IllegalStateException("injected request commit failure");
                        }
                    });
                    return saved;
                })
                .when((PaymentActionAdmissionService) AopTestUtils.getUltimateTargetObject(admission))
                .admit(any());
        assertThatThrownBy(() -> requester.request(c)).isInstanceOf(IllegalStateException.class);
        assertThat(count("payment_actions", c.paymentId())).isZero();
        assertThat(eventCount(CaptureRequested.class, c.paymentId())).isZero();
        assertThat(count("captures", c.paymentId())).isZero();
        assertThat(payments.findById(c.paymentId()).orElseThrow().getStatus().name())
                .isEqualTo("HELD");
    }

    // 완료 커밋 실패 시 P5 결제·조치·결과 발행을 되돌리고 원 이벤트 재전달로 복구한다.
    @Test
    void complete_whenCommitFails_recoversFromOriginalPublication() {
        var c = command();
        PaymentCaptureService target = AopTestUtils.getUltimateTargetObject(completion);
        doAnswer(inv -> {
                    inv.callRealMethod();
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            em.flush();
                            assertThat(eventCount(PaymentActionResult.class, c.paymentId()))
                                    .isEqualTo(1);
                            throw new IllegalStateException("injected completion failure");
                        }
                    });
                    return null;
                })
                .when(target)
                .complete(argThat(e -> e != null && e.paymentId().equals(c.paymentId())));
        requester.request(c);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(publicationStatus(publication(CaptureCompleted.class, c.paymentId())))
                        .isEqualTo("FAILED"));
        assertThat(payments.findById(c.paymentId()).orElseThrow().getStatus().name())
                .isEqualTo("HELD");
        assertThat(actions.findByPaymentId(c.paymentId())
                        .orElseThrow()
                        .getStatus()
                        .name())
                .isEqualTo("PENDING");
        assertThat(eventCount(PaymentActionResult.class, c.paymentId())).isZero();
        assertThat(requester.request(c)).isEqualTo(PaymentActionReceipt.pending());
        assertThat(eventCount(CaptureRequested.class, c.paymentId())).isEqualTo(1);
        UUID original = publication(CaptureCompleted.class, c.paymentId());
        doCallRealMethod().when(target).complete(any());
        resubmit(original);
        completed(c.paymentId());
        assertThat(publication(CaptureCompleted.class, c.paymentId())).isEqualTo(original);
        assertThat(count("captures", c.paymentId())).isEqualTo(1);
    }

    // P3 역할의 수신자가 실패해도 금융 결과는 보존되고 같은 완료를 다시 전달한다.
    @Test
    void result_whenReceiverFails_resubmitsWithoutExecutingCaptureAgain() {
        var c = command();
        state().failing.add(c.paymentId());
        requester.request(c);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> assertThat(publicationStatus(publication(PaymentActionResult.class, c.paymentId())))
                                .isEqualTo("FAILED"));
        UUID original = publication(PaymentActionResult.class, c.paymentId());
        assertThat(requester.request(c).status()).isEqualTo("ACCEPTED");
        state().failing.remove(c.paymentId());
        resubmit(original);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(state().received.get(c.paymentId())).hasSize(2);
            assertThat(jdbc.queryForObject(
                            "SELECT COMPLETION_DATE IS NOT NULL FROM EVENT_PUBLICATION WHERE ID=?",
                            Boolean.class,
                            original.toString()))
                    .isTrue();
        });
        assertThat(state().received.get(c.paymentId()).get(0))
                .isEqualTo(state().received.get(c.paymentId()).get(1));
        assertThat(count("captures", c.paymentId())).isEqualTo(1);
        assertThat(eventCount(CaptureRequested.class, c.paymentId())).isEqualTo(1);
    }

    // 같은 요청이 동시에 와도 매입 발행은 한 번이며 호출자 롤백과 P5 커밋은 분리된다.
    @Test
    void request_whenConcurrentAndCallerRollsBack_commitsOneAction() throws Exception {
        var c = command();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return requester.request(c);
            });
            var second = executor.submit(() -> {
                start.await();
                return requester.request(c);
            });
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS).status()).isIn("PENDING", "ACCEPTED");
            assertThat(second.get(15, TimeUnit.SECONDS).status()).isIn("PENDING", "ACCEPTED");
        }
        completed(c.paymentId());
        assertThat(count("payment_actions", c.paymentId())).isEqualTo(1);
        assertThat(eventCount(CaptureRequested.class, c.paymentId())).isEqualTo(1);
        var other = command();
        tx().executeWithoutResult(s -> {
            requester.request(other);
            s.setRollbackOnly();
        });
        completed(other.paymentId());
    }

    // 다른 결제가 동일 키 또는 판정으로 경합하면 실패 트랜잭션 밖에서 충돌을 판정한다.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void request_whenUniqueRace_resolvesConflictAfterRollback(boolean sameKey) throws Exception {
        var a = command();
        var other = command();
        var b = new PaymentActionCommand(
                other.paymentId(),
                sameKey ? other.decisionId() : a.decisionId(),
                "CAPTURE",
                sameKey ? a.idempotencyKey() : other.idempotencyKey());
        var barrier = new CyclicBarrier(2);
        doAnswer(inv -> {
                    // 두 요청의 사전 검사가 끝날 때까지 기다린다. 저장은 실제 Repository가 수행한다.
                    var found = em.createQuery(
                                    "select a from PaymentAction a where a.decisionId = :id", PaymentAction.class)
                            .setParameter("id", inv.getArgument(0))
                            .getResultStream()
                            .findFirst();
                    if (found.isEmpty()) barrier.await(10, TimeUnit.SECONDS);
                    return found;
                })
                .when(actions)
                .findByDecisionId(anyLong());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var fa = executor.submit(() -> requester.request(a));
            var fb = executor.submit(() -> requester.request(b));
            var ra = fa.get(20, TimeUnit.SECONDS);
            var rb = fb.get(20, TimeUnit.SECONDS);
            assertThat(List.of(ra.status(), rb.status())).containsExactlyInAnyOrder("PENDING", "REJECTED");
            var loser = "REJECTED".equals(ra.status()) ? ra : rb;
            assertThat(loser.errorCode()).isEqualTo(sameKey ? "IDEMPOTENCY_KEY_CONFLICT" : "DECISION_ACTION_CONFLICT");
            long winner = "PENDING".equals(ra.status()) ? a.paymentId() : b.paymentId();
            completed(winner);
            assertThat(count("payment_actions", a.paymentId()) + count("payment_actions", b.paymentId()))
                    .isEqualTo(1);
            assertThat(eventCount(CaptureRequested.class, a.paymentId())
                            + eventCount(CaptureRequested.class, b.paymentId()))
                    .isEqualTo(1);
        }
    }

    // CAPTURE가 먼저 선택된 결제는 다른 키의 VOID도 실행하지 않는다.
    @Test
    void request_whenCaptureAlreadyChosen_rejectsVoid() {
        var c = command();
        requester.request(c);
        completed(c.paymentId());
        var result =
                requester.request(new PaymentActionCommand(c.paymentId(), c.decisionId(), "VOID", UUID.randomUUID()));
        assertThat(result).isEqualTo(PaymentActionReceipt.rejected("PAYMENT_ACTION_CONFLICT"));
        assertThat(count("payment_actions", c.paymentId())).isEqualTo(1);
        assertThat(eventCount(CaptureRequested.class, c.paymentId())).isEqualTo(1);
        assertThat(eventCount(PaymentActionResult.class, c.paymentId())).isEqualTo(1);
    }
}
