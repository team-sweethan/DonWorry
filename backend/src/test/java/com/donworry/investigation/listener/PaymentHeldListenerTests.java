package com.donworry.investigation.listener;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.payment.api.PaymentHeld;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

// P5가 트랜잭션 안에서 PaymentHeld를 발행하고 커밋하면 P3 리스너가 Case를 만드는지 실제 MariaDB로 확인한다
// 커밋 뒤 리스너가 돌아야 하므로 롤백 테스트가 아니다. 테스트마다 결제 ID · 고객 참조를 새로 만들어 서로 섞이지 않게 한다
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class PaymentHeldListenerTests {

    static final Duration WAIT = Duration.ofSeconds(5);

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    CasePaymentRepository casePaymentRepository;

    @Autowired
    SecurityCaseRepository securityCaseRepository;

    @Autowired
    JdbcTemplate jdbc;

    // 발행 트랜잭션이 커밋되면 HELD 결제로 Case가 생기고 결제가 연결된다
    @Test
    void on_afterPublisherCommit_createsCaseWithPayment() {
        PaymentHeld event = held(newPaymentId(), customer());

        publishAndCommit(event);

        CasePayment link = awaitLink(event.paymentId());
        SecurityCase securityCase =
                securityCaseRepository.findById(link.getSecurityCase().getId()).orElseThrow();
        assertThat(securityCase.getMerchantId()).isEqualTo(event.merchantId());
        assertThat(securityCase.getCustomerRef()).isEqualTo(event.customerRef());
        assertThat(securityCase.getEvidenceVersion()).isEqualTo(1);
    }

    // 같은 HELD 통지가 두 번 와도 Case 하나, 연결 하나만 남는다
    @Test
    void on_sameEventTwice_linksPaymentOnce() {
        PaymentHeld event = held(newPaymentId(), customer());

        publishAndCommit(event);
        Long caseId = awaitLink(event.paymentId()).getSecurityCase().getId();
        publishAndCommit(event);
        // 리스너는 별도 스레드에서 돈다 — 두 번째 전달까지 처리가 끝난 뒤에 확인한다
        awaitCompletedPublications(event.paymentId(), 2);

        assertThat(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(caseId))
                .extracting(CasePayment::getPaymentId)
                .containsExactly(event.paymentId());
        assertThat(securityCaseRepository.findById(caseId).orElseThrow().getEvidenceVersion())
                .isEqualTo(1);
    }

    // 같은 고객의 두 번째 HELD가 30분 안에 오면 기존 Case에 병합되고 증거 버전이 오른다
    @Test
    void on_secondHeldOfSameCustomerWithinWindow_mergesIntoExistingCase() {
        String customer = customer();
        PaymentHeld first = held(newPaymentId(), customer);
        PaymentHeld second = held(newPaymentId(), customer);

        publishAndCommit(first);
        Long caseId = awaitLink(first.paymentId()).getSecurityCase().getId();
        publishAndCommit(second);

        assertThat(awaitLink(second.paymentId()).getSecurityCase().getId()).isEqualTo(caseId);
        assertThat(securityCaseRepository.findById(caseId).orElseThrow().getEvidenceVersion())
                .isEqualTo(2);
    }

    // P5가 롤백한 HELD 통지로는 Case를 만들지 않는다
    @Test
    void on_whenPublisherRollsBack_createsNoCase() {
        PaymentHeld event = held(newPaymentId(), customer());

        transactionTemplate.executeWithoutResult(status -> {
            events.publishEvent(event);
            status.setRollbackOnly();
        });

        // 발행 기록도 발행 트랜잭션과 함께 롤백되므로 리스너로 전달될 기록 자체가 없다
        assertThat(publicationCount(event.paymentId(), false)).isZero();
        assertThat(casePaymentRepository.findByPaymentId(event.paymentId())).isEmpty();
    }

    private void publishAndCommit(PaymentHeld event) {
        transactionTemplate.executeWithoutResult(status -> events.publishEvent(event));
    }

    // 리스너가 별도 스레드에서 돌아도 되도록 연결이 보일 때까지 기다린다
    private CasePayment awaitLink(Long paymentId) {
        return await(() -> casePaymentRepository.findByPaymentId(paymentId));
    }

    private void awaitCompletedPublications(Long paymentId, long expected) {
        await(() -> publicationCount(paymentId, true) >= expected ? Optional.of(true) : Optional.empty());
    }

    // 이 리스너 앞으로 남은 PaymentHeld 발행 기록 수 — completedOnly면 처리가 끝난 것만 센다
    private long publicationCount(Long paymentId, boolean completedOnly) {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM EVENT_PUBLICATION
                WHERE EVENT_TYPE = ?
                  AND LISTENER_ID LIKE ?
                  AND JSON_VALUE(SERIALIZED_EVENT, '$.paymentId') = ?
                  AND (? = FALSE OR COMPLETION_DATE IS NOT NULL)
                """,
                Long.class,
                PaymentHeld.class.getName(),
                "%" + PaymentHeldListener.class.getName() + "%",
                paymentId,
                completedOnly);
    }

    private static <T> T await(Supplier<Optional<T>> query) {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            Optional<T> found = query.get();
            if (found.isPresent()) {
                return found.get();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("리스너 처리 결과가 " + WAIT + " 안에 보이지 않았습니다");
    }

    private static PaymentHeld held(Long paymentId, String customerRef) {
        return PaymentHeld.builder()
                .paymentId(paymentId)
                .merchantId(1L)
                .customerRef(customerRef)
                .amount(150_000L)
                .authorizationRef("AUTH-" + paymentId)
                .evaluationId(paymentId)
                .heldAt(Instant.now())
                .build();
    }

    private static Long newPaymentId() {
        return System.nanoTime();
    }

    private static String customer() {
        return "customer-" + UUID.randomUUID();
    }
}
