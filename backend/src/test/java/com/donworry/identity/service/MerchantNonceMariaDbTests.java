package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.repository.MerchantRequestNonceRepository;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class MerchantNonceMariaDbTests {
    @Autowired
    com.donworry.identity.repository.MerchantIntegrationRepository integrations;

    @Autowired
    DatabaseMerchantNonceStore store;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    MerchantRequestNonceRepository repository;

    @MockitoBean(name = "identityClock")
    Clock clock;

    private final Instant now = Instant.parse("2026-10-27T06:00:00Z");
    private Long merchantId;
    private Long otherMerchantId;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(now);
        merchantId = merchant();
        otherMerchantId = merchant();
    }

    private Long merchant() {
        String code = "NONCE" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        jdbc.update(
                "INSERT INTO merchants (merchant_code,display_name,status,pg_fee_rate,created_at) VALUES (?, 'nonce test', 'ACTIVE', 0.01, UTC_TIMESTAMP(6))",
                code);
        return jdbc.queryForObject("SELECT merchant_id FROM merchants WHERE merchant_code=?", Long.class, code);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : new Long[] {merchantId, otherMerchantId}) {
            jdbc.update("DELETE FROM merchant_request_nonces WHERE merchant_id=?", id);
            jdbc.update("DELETE FROM merchants WHERE merchant_id=?", id);
        }
    }

    private int count() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM merchant_request_nonces WHERE merchant_id=?", Integer.class, merchantId);
    }

    // 같은 가맹점의 동시 요청은 DB UNIQUE로 정확히 하나만 소비한다
    @Test
    void consume_whenConcurrent_acceptsExactlyOnce() throws Exception {
        UUID nonce = UUID.randomUUID();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 12; i++)
                futures.add(executor.submit(() -> {
                    start.await();
                    return store.consume(merchantId, nonce, now.plusSeconds(301));
                }));
            start.countDown();
            int accepted = 0;
            for (var future : futures) if (future.get(15, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1);
        }
        assertThat(count()).isEqualTo(1);
        assertThat(store.consume(otherMerchantId, nonce, now.plusSeconds(301))).isTrue();
    }

    // 외부 업무 트랜잭션이 롤백돼도 먼저 커밋한 nonce는 남는다
    @Test
    void consume_whenOuterTransactionRollsBack_preservesNonce() {
        UUID nonce = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(store.consume(merchantId, nonce, now.plusSeconds(301))).isTrue();
            status.setRollbackOnly();
        });
        assertThat(count()).isEqualTo(1);
        assertThat(store.consume(merchantId, nonce, now.plusSeconds(301))).isFalse();
    }

    // 가맹점 잠금을 기다리는 사이 만료된 요청은 기록이 지워져도 다시 통과하지 않는다
    @Test
    void consume_whenExpiresDuringLockWait_rejectsWithoutInsert() throws Exception {
        var entering = new CountDownLatch(1);
        doAnswer(invocation -> {
                    entering.countDown();
                    return invocation.callRealMethod();
                })
                .when(repository)
                .lockMerchant(merchantId);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = new java.util.concurrent.atomic.AtomicReference<Future<Boolean>>();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForList("SELECT merchant_id FROM merchants WHERE merchant_id=? FOR UPDATE", merchantId);
                pending.set(executor.submit(() -> store.consume(merchantId, UUID.randomUUID(), now.plusSeconds(301))));
                try {
                    assertThat(entering.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    throw new IllegalStateException(exception);
                }
                when(clock.instant()).thenReturn(now.plusSeconds(301));
            });
            assertThat(pending.get().get(10, TimeUnit.SECONDS)).isFalse();
        }
        assertThat(count()).isZero();
    }

    // 저장소 인스턴스를 새로 만들어도 이전 요청의 재사용을 DB가 차단한다
    @Test
    void consume_whenStoreRecreated_preservesReplayProtection() {
        UUID nonce = UUID.randomUUID();
        assertThat(store.consume(merchantId, nonce, now.plusSeconds(301))).isTrue();
        var recreated = new DatabaseMerchantNonceStore(repository, transactions, clock, integrations);
        assertThat(recreated.consume(merchantId, nonce, now.plusSeconds(301))).isFalse();
    }

    // 정상 중복 거절의 SQL 오류에 nonce 원문이 로그로 노출되지 않는다
    @Test
    void consume_whenDuplicate_doesNotLogNonce() {
        UUID nonce = UUID.randomUUID();
        assertThat(store.consume(merchantId, nonce, now.plusSeconds(301))).isTrue();
        var logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(store.consume(merchantId, nonce, now.plusSeconds(301))).isFalse();
            assertThat(appender.list)
                    .noneMatch(event -> event.getFormattedMessage().contains(nonce.toString()));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    // FK 오류와 저장 장애를 nonce 중복으로 오인하지 않는다
    @Test
    void consume_whenDatabaseRejectsOrUnavailable_propagatesFailure() {
        assertThatThrownBy(() -> store.consume(Long.MAX_VALUE, UUID.randomUUID(), now.plusSeconds(301)))
                .isInstanceOf(DataIntegrityViolationException.class);
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(repository)
                .insertNonce(anyLong(), anyString(), anyString());
        assertThatThrownBy(() -> store.consume(merchantId, UUID.randomUUID(), now.plusSeconds(301)))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(count()).isZero();
    }

    // 만료 시각을 포함한 이전 행만 지우며 미래 시각 서명 보호 기록은 남긴다
    @Test
    void deleteExpired_whenAtBoundary_deletesOnlyExpiredRows() {
        UUID boundary = UUID.randomUUID(), future = UUID.randomUUID();
        assertThat(store.consume(merchantId, boundary, now.plusSeconds(1))).isTrue();
        assertThat(store.consume(merchantId, future, now.plusSeconds(301))).isTrue();
        when(clock.instant()).thenReturn(now.plusSeconds(1));
        store.deleteExpired();
        assertThat(count()).isEqualTo(1);
        assertThat(store.consume(merchantId, future, now.plusSeconds(301))).isFalse();
        assertThat(store.consume(merchantId, UUID.randomUUID(), now.plusSeconds(1)))
                .isFalse();
    }

    // 삭제 실패 시 아직 유효한 nonce가 남고 다음 실행에서 만료 행을 다시 정리한다
    @Test
    void deleteExpired_whenDeleteFails_retainsProtectionAndRetries() {
        UUID nonce = UUID.randomUUID();
        assertThat(store.consume(merchantId, nonce, now.plusSeconds(301))).isTrue();
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(repository)
                .deleteExpiredBatch(anyString());
        store.deleteExpired();
        assertThat(store.consume(merchantId, nonce, now.plusSeconds(301))).isFalse();
        reset(repository);
        when(clock.instant()).thenReturn(now.plusSeconds(301));
        store.deleteExpired();
        assertThat(count()).isZero();
    }

    // 1000건보다 많은 만료 기록도 다음 배치를 실행해 모두 정리한다
    @Test
    void deleteExpired_whenMoreThanBatchSize_deletesAllExpiredRows() {
        jdbc.batchUpdate(
                "INSERT INTO merchant_request_nonces (merchant_id,nonce,expires_at) VALUES (?, ?, '2026-10-27 05:59:59.999999')",
                java.util.stream.IntStream.range(0, 1001)
                        .mapToObj(
                                i -> new Object[] {merchantId, UUID.randomUUID().toString()})
                        .toList());
        store.deleteExpired();
        assertThat(count()).isZero();
    }
}
