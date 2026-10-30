package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import java.time.Instant;
import java.util.UUID;
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
class MerchantPaymentQueryMariaDbTests {
    @Autowired
    MerchantPaymentQueryService query;

    @Autowired
    PaymentRequestAdmissionService admission;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentOrderRepository orders;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    JdbcTemplate jdbc;

    private PaymentRequestCommand command() {
        return new PaymentRequestCommand(
                997L,
                UUID.randomUUID().toString(),
                "customer",
                12345L,
                Instant.now().minusSeconds(1),
                UUID.randomUUID(),
                PaymentEvaluationFixtures.riskSignals());
    }

    private PaymentRequestCommand newKey(PaymentRequestCommand c) {
        return new PaymentRequestCommand(
                c.merchantId(),
                c.orderRef(),
                c.customerRef(),
                c.amount(),
                c.orderCreatedAt(),
                UUID.randomUUID(),
                c.riskSignals());
    }

    private void deny(long id) {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        s -> payments.findByIdForUpdate(id).orElseThrow().deny());
    }

    // 단건 조회는 저장 값만 반환하며 타 가맹점 결제와 없는 결제를 같은 빈 결과로 처리한다.
    @Test
    void findPayment_whenOwned_readsStoredFieldsWithoutWriting() {
        var c = command();
        var receipt = admission.admit(c);
        var before = jdbc.queryForMap("SELECT * FROM payments WHERE payment_id=?", receipt.paymentId());
        var requestBefore = jdbc.queryForMap("SELECT * FROM payment_requests WHERE payment_id=?", receipt.paymentId());
        var result = query.findPayment(c.merchantId(), receipt.paymentId()).orElseThrow();
        assertThat(result.paymentId()).isEqualTo(receipt.paymentId());
        assertThat(result.orderRef()).isEqualTo(c.orderRef());
        assertThat(result.attemptNo()).isEqualTo(1);
        assertThat(result.amount()).isEqualTo(c.amount());
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(result.statusChangedAt()).isEqualTo(result.requestedAt());
        assertThat(result.declineCode()).isNull();
        assertThat(query.findPayment(998L, receipt.paymentId())).isEmpty();
        assertThat(query.findPayment(c.merchantId(), Long.MAX_VALUE)).isEmpty();
        assertThat(jdbc.queryForMap("SELECT * FROM payments WHERE payment_id=?", receipt.paymentId()))
                .isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_requests WHERE payment_id=?", receipt.paymentId()))
                .isEqualTo(requestBefore);
    }

    // 상태 전이 커밋은 다음 조회에 반영되고 과거 응답 객체는 변하지 않는다.
    @Test
    void findPayment_afterDecline_returnsCommittedMetadata() {
        var c = command();
        var id = admission.admit(c).paymentId();
        var before = query.findPayment(c.merchantId(), id).orElseThrow();
        deny(id);
        var after = query.findPayment(c.merchantId(), id).orElseThrow();
        assertThat(after.paymentStatus()).isEqualTo(PaymentStatus.DENIED);
        assertThat(after.declineCode()).isEqualTo("FDS_REJECTED");
        assertThat(after.statusChangedAt()).isAfterOrEqualTo(before.requestedAt());
        assertThat(before.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    // 과거 결제는 단건으로 읽되 알 수 없는 시도 번호·상태 변경 시각을 만들지 않는다.
    @Test
    void findPayment_whenLegacy_preservesNullMetadata() {
        var c = command();
        var id = new TransactionTemplate(manager)
                .execute(s -> payments.save(Payment.pending(
                                c.merchantId(), c.customerRef(), c.orderRef(), c.amount(), Instant.now()))
                        .getPaymentId());
        jdbc.update("UPDATE payments SET status_changed_at=NULL WHERE payment_id=?", id);
        var result = query.findPayment(c.merchantId(), id).orElseThrow();
        assertThat(result.attemptNo()).isNull();
        assertThat(result.statusChangedAt()).isNull();
        assertThat(result.declineCode()).isNull();
        assertThat(query.findOrder(c.merchantId(), c.orderRef(), null, null)).isEmpty();
    }

    // 최신 시도는 페이지 커서와 무관하고 이력은 순번 내림차순으로 중복 없이 이어진다.
    @Test
    void findOrder_whenPaged_returnsLatestAndCursorHistory() {
        var c = command();
        var first = admission.admit(c);
        deny(first.paymentId());
        var second = admission.admit(newKey(c));
        deny(second.paymentId());
        var third = admission.admit(newKey(c));
        var page = query.findOrder(c.merchantId(), c.orderRef(), 2, null).orElseThrow();
        assertThat(page.latestPayment().paymentId()).isEqualTo(third.paymentId());
        assertThat(page.attempts()).extracting(r -> r.attemptNo()).containsExactly(3L, 2L);
        assertThat(page.nextBeforeAttemptNo()).isEqualTo(2);
        var next = query.findOrder(c.merchantId(), c.orderRef(), 2, page.nextBeforeAttemptNo())
                .orElseThrow();
        assertThat(next.latestPayment()).isEqualTo(page.latestPayment());
        assertThat(next.attempts()).extracting(r -> r.paymentId()).containsExactly(first.paymentId());
        assertThat(next.nextBeforeAttemptNo()).isNull();
        assertThat(query.findOrder(c.merchantId(), c.orderRef(), 2, 1L)
                        .orElseThrow()
                        .attempts())
                .isEmpty();
        assertThat(query.findOrder(998L, c.orderRef(), null, null)).isEmpty();
        assertThatThrownBy(() -> page.attempts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(query.findOrder(c.merchantId(), c.orderRef(), null, null)
                        .orElseThrow()
                        .attempts())
                .hasSize(3);
        assertThat(query.findOrder(c.merchantId(), c.orderRef(), 100, null)
                        .orElseThrow()
                        .attempts())
                .hasSize(3);
    }

    // 다음 페이지 사이에 새 시도가 생겨도 커서 아래 이력을 반복하지 않는다.
    @Test
    void findOrder_afterNewAttempt_preservesCursorAndUpdatesLatest() {
        var c = command();
        var first = admission.admit(c);
        deny(first.paymentId());
        var second = admission.admit(newKey(c));
        deny(second.paymentId());
        var page = query.findOrder(c.merchantId(), c.orderRef(), 1, null).orElseThrow();
        var third = admission.admit(newKey(c));
        var next = query.findOrder(c.merchantId(), c.orderRef(), 1, page.nextBeforeAttemptNo())
                .orElseThrow();
        assertThat(next.latestPayment().paymentId()).isEqualTo(third.paymentId());
        assertThat(next.attempts()).extracting(r -> r.paymentId()).containsExactly(first.paymentId());
        assertThat(next.nextBeforeAttemptNo()).isNull();
    }

    // 시도 없는 주문 행을 접수된 결제로 표시하지 않는다.
    @Test
    void findOrder_whenNoAttempts_returnsEmpty() {
        var c = command();
        new TransactionTemplate(manager)
                .executeWithoutResult(s -> orders.save(PaymentOrder.register(
                        c.merchantId(), c.orderRef(), c.customerRef(), c.amount(), c.orderCreatedAt(), Instant.now())));
        assertThat(query.findOrder(c.merchantId(), c.orderRef(), null, null)).isEmpty();
    }
}
