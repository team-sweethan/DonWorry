package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.entity.PaymentStatus;
import com.donworry.payment.repository.PaymentRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class PaymentCreationServiceTests {

    @Autowired
    PaymentCreationService paymentCreationService;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    // 서비스 반환 시 커밋된 최초 결제를 새 트랜잭션에서 조회할 수 있다
    @Test
    void createPending_withValidInput_commitsPaymentAndReturnsId() {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        Long paymentId = paymentCreationService.createPending(999L, "customer-service", "order-service", 300_000L);

        Instant after = Instant.now();
        assertThat(paymentId).isPositive();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(status -> {
            Payment payment = paymentRepository.findById(paymentId).orElseThrow();
            assertThat(payment.getMerchantId()).isEqualTo(999L);
            assertThat(payment.getCustomerRef()).isEqualTo("customer-service");
            assertThat(payment.getOrderRef()).isEqualTo("order-service");
            assertThat(payment.getAmount()).isEqualTo(300_000L);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getAuthorizationRef()).isNull();
            assertThat(payment.getCreatedAt()).isBetween(before, after);
        });
    }

    // 호출자 트랜잭션이 롤백돼도 먼저 커밋한 PENDING 기록은 유지된다
    @Test
    void createPending_whenCallerRollsBack_preservesInitialPayment() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        Long paymentId = transactions.execute(status -> {
            Long createdId = paymentCreationService.createPending(
                    999L, "customer-caller-rollback", "order-caller-rollback", 500_000L);
            status.setRollbackOnly();
            return createdId;
        });

        assertThat(paymentId).isPositive();
        transactions.executeWithoutResult(status -> {
            Payment payment = paymentRepository.findById(paymentId).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getAmount()).isEqualTo(500_000L);
        });
    }

    // 반환한 평가 재료와 상태가 커밋 후 재조회한 결제의 값과 모두 일치한다
    @Test
    void createPendingSnapshot_withValidInput_returnsCommittedValues() {
        var snapshot =
                paymentCreationService.createPendingSnapshot(999L, "customer-snapshot", "order-snapshot", 150_000L);

        assertThat(snapshot.paymentId()).isPositive();
        assertThat(snapshot.customerRef()).isEqualTo("customer-snapshot");
        assertThat(snapshot.amount()).isEqualTo(150_000L);
        assertThat(snapshot.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(snapshot.authorizationRef()).isNull();
        assertThat(snapshot.createdAt()).isNotNull();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(status -> {
            Payment payment = paymentRepository.findById(snapshot.paymentId()).orElseThrow();
            assertThat(PaymentCreationSnapshot.from(payment)).isEqualTo(snapshot);
            assertThat(payment.getMerchantId()).isEqualTo(999L);
            assertThat(payment.getOrderRef()).isEqualTo("order-snapshot");
        });
    }

    // 호출자가 롤백해도 반환한 스냅샷과 같은 최초 결제 기록이 남는다
    @Test
    void createPendingSnapshot_whenCallerRollsBack_preservesInitialPayment() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        var snapshot = transactions.execute(status -> {
            var created = paymentCreationService.createPendingSnapshot(
                    999L, "customer-snapshot-rollback", "order-snapshot-rollback", 150_000L);
            status.setRollbackOnly();
            return created;
        });

        assertThat(snapshot).isNotNull();
        transactions.executeWithoutResult(status -> {
            Payment payment = paymentRepository.findById(snapshot.paymentId()).orElseThrow();
            assertThat(PaymentCreationSnapshot.from(payment)).isEqualTo(snapshot);
        });
    }

    // 필수값이 없는 호출은 실패하고 결제 행을 생성하지 않는다
    @Test
    void createPending_withMissingRequiredValue_doesNotPersistPayment() {
        Long countBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payments", Long.class);

        assertThatNullPointerException()
                .isThrownBy(() -> paymentCreationService.createPending(999L, null, "order-invalid-service", 300_000L));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payments", Long.class))
                .isEqualTo(countBefore);
    }
}
