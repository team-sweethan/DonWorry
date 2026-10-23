package com.donworry.payment.entity;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentAuthorizationTests {
    private Payment pending() {
        return Payment.pending(1L, "customer", "order", 1000L, Instant.now());
    }

    // 잘못된 승인 참조는 상태·참조를 부분 변경하지 않는다.
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "oversized"})
    void recordAuthorizationSuccess_whenReferenceInvalid_preservesState(String reference) {
        var payment = pending();
        String value = "oversized".equals(reference) ? "a".repeat(101) : reference;
        assertThatThrownBy(() -> payment.recordAuthorizationSuccess(value, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getAuthorizationRef()).isNull();
    }

    // 승인·차단·거절 후 모든 최초 처리 메서드의 재호출을 막는다.
    @ParameterizedTest
    @ValueSource(strings = {"allow", "review", "deny", "decline"})
    void transition_whenAlreadyProcessed_rejectsAllInitialTransitions(String operation) {
        var payment = pending();
        switch (operation) {
            case "allow" -> payment.recordAuthorizationSuccess("ref", false);
            case "review" -> payment.recordAuthorizationSuccess("ref", true);
            case "deny" -> payment.deny();
            default -> payment.declineAuthorization();
        }
        var status = payment.getStatus();
        var reference = payment.getAuthorizationRef();
        assertThatThrownBy(payment::deny).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(payment::declineAuthorization).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> payment.recordAuthorizationSuccess("new", false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(payment.getStatus()).isEqualTo(status);
        assertThat(payment.getAuthorizationRef()).isEqualTo(reference);
    }
}
