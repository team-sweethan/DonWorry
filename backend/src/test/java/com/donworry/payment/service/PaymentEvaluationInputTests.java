package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentEvaluationInputTests {

    // 호출마다 요청 식별자나 결제 요청 시각을 새로 만들지 않고 입력을 보존한다.
    @Test
    void input_whenValid_preservesRequestIdentityAndTime() {
        var input = PaymentEvaluationFixtures.validInput().build();

        assertThat(input.evaluationRequestId()).isEqualTo(PaymentEvaluationFixtures.REQUEST_ID);
        assertThat(input.paymentRequestedAt()).isEqualTo(PaymentEvaluationFixtures.REQUESTED_AT);
        assertThat(input.paymentId()).isEqualTo(101L);
        assertThat(input.customerRef()).isEqualTo("customer-001");
        assertThat(input.amount()).isEqualTo(150_000L);
    }

    // 빌더에서 필수 참조값을 누락하면 호출 전에 거절한다.
    @Test
    void input_whenRequiredReferenceMissing_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> PaymentEvaluationFixtures.validInput()
                        .evaluationRequestId(null)
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        PaymentEvaluationFixtures.validInput().paymentId(null).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentEvaluationFixtures.validInput()
                        .paymentRequestedAt(null)
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 비어 있거나 공백뿐인 고객 참조는 평가 입력으로 허용하지 않는다.
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void input_whenCustomerRefBlank_throwsIllegalArgumentException(String customerRef) {
        assertThatThrownBy(() -> PaymentEvaluationFixtures.validInput()
                        .customerRef(customerRef)
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 결제 ID와 금액은 모두 양수여야 한다.
    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void input_whenIdOrAmountNonPositive_throwsIllegalArgumentException(long value) {
        assertThatThrownBy(() ->
                        PaymentEvaluationFixtures.validInput().paymentId(value).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        PaymentEvaluationFixtures.validInput().amount(value).build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
