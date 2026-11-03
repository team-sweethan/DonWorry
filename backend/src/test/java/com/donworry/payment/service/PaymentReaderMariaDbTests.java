package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.payment.api.PaymentReader;
import com.donworry.payment.api.PaymentSummary;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.repository.PaymentRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class PaymentReaderMariaDbTests {
    @Autowired
    PaymentReader reader;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    PaymentRepository payments;

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private Payment pending(Instant createdAt) {
        return Payment.pending(
                999L, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 123_456L, createdAt);
    }

    private long save(Instant createdAt) {
        return transaction().execute(status -> payments.save(pending(createdAt)).getPaymentId());
    }

    // 세션 없이 공개 인터페이스를 호출해 실제 읽기 전용 트랜잭션에서 저장값을 읽는다.
    @Test
    void findById_withoutSession_readsStoredValuesInReadOnlyTransaction() {
        var previous = SecurityContextHolder.getContext();
        SecurityContextHolder.clearContext();
        try {
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            var nanos = Instant.parse("2026-10-25T03:04:05.123456789Z");
            long id = save(nanos);
            var before = jdbc.queryForMap("SELECT * FROM payments WHERE payment_id = ?", id);
            doAnswer(invocation -> {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                                .isTrue();
                        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                                .isTrue();
                        // 트랜잭션만 관찰하고 조회는 실제 EntityManager와 MariaDB로 수행한다.
                        return Optional.ofNullable(entityManager.find(Payment.class, id));
                    })
                    .when(payments)
                    .findById(id);

            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            assertThat(reader.findById(id))
                    .contains(new PaymentSummary(id, 123_456L, "PENDING", nanos.truncatedTo(ChronoUnit.MICROS)));
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            // DATETIME 원본을 로컬 JDBC 시간대 변환 없이 확인한다.
            assertThat(jdbc.queryForObject(
                            "SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f') FROM payments WHERE payment_id = ?",
                            String.class, id))
                    .isEqualTo("2026-10-25 03:04:05.123456");
            assertThat(jdbc.queryForMap("SELECT * FROM payments WHERE payment_id = ?", id))
                    .isEqualTo(before);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    // 상태 변경 커밋은 새 조회에 반영되며 이전 DTO는 변경되지 않는다.
    @Test
    void findById_afterStateCommit_returnsNewStateWithoutChangingPreviousSnapshot() {
        long id = save(Instant.parse("2026-10-25T03:04:05.123456Z"));
        var initial = reader.findById(id).orElseThrow();
        transaction()
                .executeWithoutResult(status ->
                        payments.findByIdForUpdate(id).orElseThrow().recordAuthorizationSuccess("reader-auth", false));
        var authorized = reader.findById(id).orElseThrow();
        assertThat(authorized).isEqualTo(initial);
        transaction()
                .executeWithoutResult(
                        status -> payments.findByIdForUpdate(id).orElseThrow().completeCapture());

        var captured = reader.findById(id).orElseThrow();
        assertThat(captured).isEqualTo(new PaymentSummary(id, initial.amount(), "CAPTURED", initial.requestedAt()));
        assertThat(initial.paymentStatus()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT authorization_ref FROM payments WHERE payment_id = ?", String.class, id))
                .isEqualTo("reader-auth");
    }

    // 호출자 트랜잭션의 변경은 보이지만 롤백 후 새 조회에는 남지 않는다.
    @Test
    void findById_withCallerTransaction_participatesAndDoesNotCommitCallerChanges() {
        long id = save(Instant.parse("2026-10-25T03:04:05Z"));
        transaction().executeWithoutResult(status -> {
            var payment = payments.findByIdForUpdate(id).orElseThrow();
            payment.recordAuthorizationSuccess("rolled-back-auth", true);
            entityManager.flush();
            assertThat(reader.findById(id).orElseThrow().paymentStatus()).isEqualTo("HELD");
            status.setRollbackOnly();
        });
        assertThat(reader.findById(id).orElseThrow().paymentStatus()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT authorization_ref FROM payments WHERE payment_id = ?", String.class, id))
                .isNull();
    }

    // 실제 Spring Bean에서도 입력 오류와 없는 결제를 구분한다.
    @Test
    void findById_whenInvalidOrMissing_distinguishesInputErrorFromEmpty() {
        assertThatIllegalArgumentException().isThrownBy(() -> reader.findById(null));
        assertThatIllegalArgumentException().isThrownBy(() -> reader.findById(0L));
        assertThatIllegalArgumentException().isThrownBy(() -> reader.findById(-1L));
        assertThat(reader.findById(Long.MAX_VALUE)).isEmpty();
    }
    // 고객별 최근 결제 (#338) — customer_ref는 가맹점마다 따로 매기는 값이라 가맹점이 다르면 다른 고객이다
    @Test
    void findRecentByCustomer_sameCustomerRefOtherMerchant_isExcluded() {
        String customer = UUID.randomUUID().toString();
        Instant at = Instant.parse("2026-10-25T03:00:00Z");
        long mine = saveFor(999L, customer, at);
        saveFor(998L, customer, at.plusSeconds(1));
        saveFor(999L, UUID.randomUUID().toString(), at.plusSeconds(2));

        assertThat(reader.findRecentByCustomer(999L, customer, at.minusSeconds(60), List.of(), 20))
                .extracting(PaymentSummary::paymentId)
                .containsExactly(mine);
    }

    // since보다 오래된 결제는 빼고, 최근 순으로 limit건만 읽는다 (#338)
    @Test
    void findRecentByCustomer_olderThanSince_excluded_limitsNewestFirst() {
        String customer = UUID.randomUUID().toString();
        Instant since = Instant.parse("2026-10-01T00:00:00Z");
        saveFor(999L, customer, since.minusSeconds(1));
        long first = saveFor(999L, customer, since);
        long second = saveFor(999L, customer, since.plusSeconds(10));
        long third = saveFor(999L, customer, since.plusSeconds(20));

        assertThat(reader.findRecentByCustomer(999L, customer, since, List.of(), 2))
                .extracting(PaymentSummary::paymentId)
                .containsExactly(third, second);
        assertThat(reader.findRecentByCustomer(999L, customer, since, List.of(), 20))
                .extracting(PaymentSummary::paymentId)
                .isEqualTo(List.of(third, second, first));
    }

    // 뺄 결제는 limit을 세기 전에 빠진다 — 최근 결제가 모두 뺄 대상이어도 그 전 결제로 limit을 채운다 (#338 리뷰)
    @Test
    void findRecentByCustomer_excludedIds_skippedBeforeLimit() {
        String customer = UUID.randomUUID().toString();
        Instant since = Instant.parse("2026-10-01T00:00:00Z");
        long older = saveFor(999L, customer, since.plusSeconds(10));
        long excludedFirst = saveFor(999L, customer, since.plusSeconds(20));
        long excludedSecond = saveFor(999L, customer, since.plusSeconds(30));

        assertThat(reader.findRecentByCustomer(999L, customer, since, List.of(excludedFirst, excludedSecond), 1))
                .extracting(PaymentSummary::paymentId)
                .containsExactly(older);
    }

    private long saveFor(Long merchantId, String customerRef, Instant createdAt) {
        return transaction()
                .execute(status -> payments.save(Payment.pending(
                                merchantId, customerRef, UUID.randomUUID().toString(), 123_456L, createdAt))
                        .getPaymentId());
    }
}
