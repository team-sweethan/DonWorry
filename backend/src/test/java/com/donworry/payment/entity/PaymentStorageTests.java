package com.donworry.payment.entity;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentStorageTests {
    private static final Instant TIME = Instant.parse("2026-10-25T00:00:00.123456Z");

    // 최초 주문 기한 경계에서는 새 시도를 허용하지 않는다
    @Test
    void orderDeadline_isOriginalTimeAndExclusive() {
        var order = PaymentOrder.register(1L, "order", "customer", 100L, TIME, TIME.plusSeconds(30));
        assertThat(order.allocateAttempt(TIME.plusSeconds(599))).isEqualTo(1);
        assertThatIllegalStateException().isThrownBy(() -> order.allocateAttempt(TIME.plusSeconds(600)));
        assertThat(order.getLastAttemptNo()).isEqualTo(1);
        assertThat(order.matches("order", "customer", 101L, TIME)).isFalse();
        assertThat(order.matches("order", "customer", 100L, TIME.plusSeconds(1)))
                .isFalse();
    }

    // 잘못된 주문 입력은 저장 전에 거절한다
    @Test
    void order_rejectsInvalidValuesBeforeStorage() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PaymentOrder.register(0L, "order", "customer", 100L, TIME, TIME));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PaymentOrder.register(1L, " order", "customer", 100L, TIME, TIME));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PaymentOrder.register(1L, "order", "customer", 0L, TIME, TIME));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PaymentOrder.register(1L, "order", "customer", 100L, TIME.plusSeconds(1), TIME));
    }

    // 같은 결제 상태의 재반영은 최초 전이 시각을 보존한다
    @Test
    void paymentMetadata_changesOnlyOnStateTransition() {
        var payment = Payment.pending(1L, "customer", "order", 100L, TIME);
        assertThat(payment.getStatusChangedAt()).isEqualTo(TIME);
        payment.recordAuthorizationSuccess("auth", false);
        assertThat(payment.getStatusChangedAt()).isEqualTo(TIME);
        payment.completeCapture();
        var capturedAt = payment.getStatusChangedAt();
        assertThat(capturedAt).isAfter(TIME);
        payment.completeCapture();
        assertThat(payment.getStatusChangedAt()).isEqualTo(capturedAt);
        assertThat(payment.getDeclineCode()).isNull();
    }

    // FDS 거절과 모의 카드 거절을 구분한다
    @Test
    void paymentMetadata_distinguishesFdsAndCardDecline() {
        var denied = Payment.pending(1L, "customer", "order", 100L, TIME);
        denied.deny();
        assertThat(denied.getDeclineCode()).isEqualTo("FDS_REJECTED");
        var declined = Payment.pending(1L, "customer", "order", 100L, TIME);
        declined.declineAuthorization();
        assertThat(declined.getDeclineCode()).isEqualTo("AUTHORIZATION_DECLINED");
    }

    // 오류 응답에 성공 평가 값을 섞지 않는다
    @Test
    void errorSnapshot_cannotMasqueradeAsSuccessfulEvaluation() {
        var id = UUID.randomUUID();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentEvaluationSnapshot(id, 1L, "ERROR", 10, "TIMEOUT", TIME));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentEvaluationSnapshot(id, 1L, "ALLOW", null, null, TIME));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentEvaluationSnapshot(id, 1L, "ERROR", null, null, TIME));
    }
}
