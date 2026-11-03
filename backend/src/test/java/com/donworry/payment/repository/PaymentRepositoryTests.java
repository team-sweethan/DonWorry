package com.donworry.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.entity.PaymentStatus;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class PaymentRepositoryTests {

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    // 저장 커밋 후 새 트랜잭션에서 생성된 ID로 결제 전체 필드를 조회한다
    @Test
    void save_afterCommit_findsPaymentByGeneratedId() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Instant createdAt = Instant.parse("2026-10-22T08:00:00.123456Z");

        Long paymentId = transactions.execute(status -> {
            Payment payment = Payment.pending(999L, "customer-repository", "order-repository", 300_000L, createdAt);
            return paymentRepository.save(payment).getPaymentId();
        });

        assertThat(paymentId).isPositive();
        transactions.executeWithoutResult(status -> {
            Payment payment = paymentRepository.findById(paymentId).orElseThrow();
            assertThat(payment.getPaymentId()).isEqualTo(paymentId);
            assertThat(payment.getMerchantId()).isEqualTo(999L);
            assertThat(payment.getCustomerRef()).isEqualTo("customer-repository");
            assertThat(payment.getOrderRef()).isEqualTo("order-repository");
            assertThat(payment.getAmount()).isEqualTo(300_000L);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getAuthorizationRef()).isNull();
            assertThat(payment.getCreatedAt()).isEqualTo(createdAt);
        });
    }

    // ID가 발급됐어도 저장 트랜잭션이 롤백되면 결제가 남지 않는다
    @Test
    void save_afterRollback_doesNotPersistPayment() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        Long paymentId = transactions.execute(status -> {
            Payment saved = paymentRepository.save(Payment.pending(
                    999L, "customer-rollback", "order-rollback", 300_000L, Instant.parse("2026-10-22T08:00:00Z")));
            status.setRollbackOnly();
            return saved.getPaymentId();
        });

        assertThat(paymentId).isPositive();
        transactions.executeWithoutResult(
                status -> assertThat(paymentRepository.findById(paymentId)).isEmpty());
    }

    // 없는 ID는 임의 결제나 예외 대신 빈 조회 결과로 반환한다
    @Test
    void findById_withMissingPayment_returnsEmpty() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        transactions.executeWithoutResult(
                status -> assertThat(paymentRepository.findById(Long.MAX_VALUE)).isEmpty());
    }
}
