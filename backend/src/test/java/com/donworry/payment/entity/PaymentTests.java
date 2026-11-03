package com.donworry.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentTests {

    private static final Instant CREATED_AT = Instant.parse("2026-10-22T08:00:00.123456789Z");

    // 신규 결제는 승인 전 PENDING이며 DB와 같은 시각 정밀도를 사용한다
    @Test
    void pending_withValidInput_createsUnapprovedPayment() {
        Payment payment = Payment.pending(1L, "customer-1", "order-1", 300_000L, CREATED_AT);

        assertThat(payment.getPaymentId()).isNull();
        assertThat(payment.getMerchantId()).isEqualTo(1L);
        assertThat(payment.getCustomerRef()).isEqualTo("customer-1");
        assertThat(payment.getOrderRef()).isEqualTo("order-1");
        assertThat(payment.getAmount()).isEqualTo(300_000L);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getAuthorizationRef()).isNull();
        assertThat(payment.getCreatedAt()).isEqualTo(Instant.parse("2026-10-22T08:00:00.123456Z"));
    }

    // 필수 컬럼 값이 빠지면 저장 전에 생성 단계에서 거절한다
    @ParameterizedTest
    @MethodSource("missingRequiredValues")
    void pending_withMissingRequiredValue_throwsException(
            Long merchantId, String customerRef, String orderRef, Long amount, Instant createdAt) {
        assertThatNullPointerException()
                .isThrownBy(() -> Payment.pending(merchantId, customerRef, orderRef, amount, createdAt));
    }

    static Stream<Arguments> missingRequiredValues() {
        return Stream.of(
                Arguments.of(null, "customer-1", "order-1", 300_000L, CREATED_AT),
                Arguments.of(1L, null, "order-1", 300_000L, CREATED_AT),
                Arguments.of(1L, "customer-1", null, 300_000L, CREATED_AT),
                Arguments.of(1L, "customer-1", "order-1", null, CREATED_AT),
                Arguments.of(1L, "customer-1", "order-1", 300_000L, null));
    }

    // 참조 문자열이 SQL 길이를 넘으면 잘라 저장하지 않고 거절한다
    @ParameterizedTest
    @MethodSource("oversizedReferences")
    void pending_withOversizedReference_throwsException(String customerRef, String orderRef) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Payment.pending(1L, customerRef, orderRef, 300_000L, CREATED_AT));
    }

    static Stream<Arguments> oversizedReferences() {
        return Stream.of(
                Arguments.of("a".repeat(101), "order-1"),
                Arguments.of("customer-1", "a".repeat(101)),
                Arguments.of("😀".repeat(101), "order-1"));
    }

    // VARCHAR 길이는 보조 평면 문자를 포함해 문자 수로 검사한다
    @Test
    void pending_withMaximumLengthReferences_preservesValues() {
        String reference = "😀".repeat(100);

        Payment payment = Payment.pending(1L, reference, reference, 300_000L, CREATED_AT);

        assertThat(payment.getCustomerRef()).isEqualTo(reference);
        assertThat(payment.getOrderRef()).isEqualTo(reference);
    }
}
