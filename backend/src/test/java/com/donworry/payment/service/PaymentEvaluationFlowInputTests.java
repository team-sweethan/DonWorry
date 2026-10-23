package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PaymentEvaluationFlowInputTests {
    // 누락·음수·공백·길이 초과 입력은 저장 전에 거절한다
    @Test
    void constructor_withInvalidFields_rejectsInput() {
        for (Long invalid : new Long[] {null, 0L, -1L}) {
            assertThatThrownBy(() -> new PaymentEvaluationFlowInput(invalid, "customer", "order", 1L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PaymentEvaluationFlowInput(1L, "customer", "order", invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String invalid : new String[] {null, "", " \t", "가".repeat(101)}) {
            assertThatThrownBy(() -> new PaymentEvaluationFlowInput(1L, invalid, "order", 1L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PaymentEvaluationFlowInput(1L, "customer", invalid, 1L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // 보조 평면 문자도 코드 포인트로 길이를 세며 유효한 입력을 임의로 다듬지 않는다
    @Test
    void constructor_withBoundaryValues_preservesInput() {
        String customer = "😀".repeat(100);
        var input = PaymentEvaluationFlowInput.builder()
                .merchantId(1L)
                .customerRef(customer)
                .orderRef(" order ")
                .amount(Long.MAX_VALUE)
                .build();
        assertThat(input.customerRef()).isEqualTo(customer);
        assertThat(input.orderRef()).isEqualTo(" order ");
        assertThat(input.amount()).isEqualTo(Long.MAX_VALUE);
    }
}
