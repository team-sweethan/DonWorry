package com.donworry.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDateTime;
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
class PaymentMappingTests {

    @PersistenceContext
    EntityManager entityManager;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    // Flyway 스키마에 저장한 결제는 커밋 후 새 트랜잭션에서도 같은 값으로 조회된다
    @Test
    void payment_afterCommit_preservesMappingAndUtcTimestamp() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Instant createdAt = Instant.parse("2026-10-22T08:00:00.123456789Z");
        String reference = "😀".repeat(100);

        Long paymentId = transactions.execute(status -> {
            Payment payment = Payment.pending(999L, reference, "order-mapping", 300_000L, createdAt);
            entityManager.persist(payment);
            return payment.getPaymentId();
        });

        assertThat(paymentId).isPositive();
        transactions.executeWithoutResult(status -> {
            Payment payment = entityManager.find(Payment.class, paymentId);
            assertThat(payment.getMerchantId()).isEqualTo(999L);
            assertThat(payment.getCustomerRef()).isEqualTo(reference);
            assertThat(payment.getOrderRef()).isEqualTo("order-mapping");
            assertThat(payment.getAmount()).isEqualTo(300_000L);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getAuthorizationRef()).isNull();
            assertThat(payment.getCreatedAt()).isEqualTo(Instant.parse("2026-10-22T08:00:00.123456Z"));
        });

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM payments WHERE payment_id = ?", String.class, paymentId))
                .isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT created_at FROM payments WHERE payment_id = ?", LocalDateTime.class, paymentId))
                .isEqualTo(LocalDateTime.parse("2026-10-22T08:00:00.123456"));
    }
}
