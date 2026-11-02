package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.investigation.dto.CaseDetailResponse;
import com.donworry.investigation.service.CaseIntakeService;
import com.donworry.investigation.service.CaseQueryService;
import com.donworry.investigation.service.HeldPayment;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.repository.PaymentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 실제 P5 고객 HELD 승인 취소 → 운영 발행(PaymentCustomerVoidEventPublisher) → P3 수신 → Case 반영까지 (#382)
// 대역 없이 실제 P5 원본 조회(PaymentCustomerVoidReader)와 P3 Case 정책(모두 취소 → 종료)을 함께 본다
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentCustomerVoidCaseMariaDbTests {

    static final long MERCHANT_ID = 9982L;

    @Autowired
    PaymentCancellationAdmissionService customers;

    @Autowired
    PaymentRepository payments;

    @Autowired
    CaseIntakeService caseIntakeService;

    @Autowired
    CaseQueryService caseQueryService;

    @Autowired
    PlatformTransactionManager manager;

    // HELD가 먼저 Case에 연결되고 고객이 취소하면, 성공 이벤트를 받은 P3가 Case를 닫는다
    @Test
    void customerVoid_afterCaseLinked_closesCaseByEvent() {
        Payment payment = heldPayment();
        Long caseId = link(payment);

        var result = customers.admit(command(payment));
        assertThat(result.requestStatus()).isEqualTo("SUCCEEDED");

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertClosedByCustomer(caseId, result.completedAt()));
    }

    // 고객 취소가 HELD 연결보다 먼저 확정되면(순서 역전) 연결 커밋 직후 P5 원본 확인으로 반영한다
    @Test
    void customerVoid_beforeCaseLinked_reflectsOnLink() {
        Payment payment = heldPayment();
        var result = customers.admit(command(payment));
        assertThat(result.requestStatus()).isEqualTo("SUCCEEDED");

        Long caseId = link(payment);

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertClosedByCustomer(caseId, result.completedAt()));
    }

    private void assertClosedByCustomer(Long caseId, Instant completedAt) {
        CaseDetailResponse detail = caseQueryService.detail(caseId);
        assertThat(detail.securityCase().status()).isEqualTo("CLOSED");
        assertThat(detail.payments()).singleElement().satisfies(payment -> {
            assertThat(payment.customerCanceledAt()).isEqualTo(completedAt);
            assertThat(payment.paymentStatus()).isEqualTo("VOIDED");
        });
        assertThat(detail.history())
                .extracting(CaseDetailResponse.HistoryResponse::eventType)
                .containsOnlyOnce("PAYMENT_CANCELED_BY_CUSTOMER")
                .contains("CASE_CLOSED");
        assertThat(detail.decisions()).isEmpty();
    }

    private Payment heldPayment() {
        return new TransactionTemplate(manager).execute(status -> {
            var payment = Payment.pending(
                    MERCHANT_ID, "customer-void-case", UUID.randomUUID().toString(), 3000L, Instant.now());
            payment.recordAuthorizationSuccess("stored-authorization", true);
            return payments.save(payment);
        });
    }

    // P5 HELD 통지를 받은 것처럼 Case에 연결한다 — 리스너처럼 트랜잭션 밖에서 불러 커밋 뒤 확인이 돈다
    private Long link(Payment payment) {
        return caseIntakeService.receive(new HeldPayment(
                payment.getPaymentId(),
                payment.getMerchantId(),
                MERCHANT_ID + ":" + UUID.randomUUID(),
                payment.getAmount(),
                payment.getAuthorizationRef(),
                null,
                Instant.now()));
    }

    private static PaymentCancellationCommand command(Payment payment) {
        return new PaymentCancellationCommand(
                payment.getMerchantId(), payment.getPaymentId(), UUID.randomUUID(), "고객 취소");
    }
}
