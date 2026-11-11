package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.dto.OrderContextToolResponse;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.payment.api.PaymentOrderReader;
import com.donworry.payment.api.PaymentOrderSummary;
import com.donworry.payment.api.PaymentReader;
import com.donworry.payment.api.PaymentSummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

// AI Tool get_order_context (#634) — P5 창구는 목으로 바꿔 사건 범위 · 내보내는 칸만 본다
@ExtendWith(MockitoExtension.class)
class OrderContextFinderTests {

    static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    static final Instant ORDERED_AT = Instant.parse("2026-11-06T23:59:00Z");

    @Mock
    SecurityCaseRepository securityCaseRepository;

    @Mock
    CasePaymentRepository casePaymentRepository;

    @Mock
    PaymentOrderReader paymentOrderReader;

    @Mock
    PaymentReader paymentReader;

    // 주문 정보 응답에는 주문자 이름 · 전화 원문과 전화 지문이 없다
    @Test
    void orderContext_response_excludesRawContactAndFingerprint() {
        givenCase7WithPayments(3L);
        when(paymentOrderReader.findOrders(List.of(3L))).thenReturn(List.of(order(3L, "order-a", 1)));
        when(paymentReader.findById(3L)).thenReturn(Optional.of(payment(3L)));

        OrderContextToolResponse response = finder().find(7L, NOW);

        // ai-server · 보고서로 나가는 JSON 칸을 고정한다 — 이름 · 전화 · 지문 칸이 생기면 여기서 깨진다 (ADR-0082)
        JsonMapper mapper = JsonMapper.builder().build();
        Map<?, ?> json = mapper.readValue(mapper.writeValueAsString(response), Map.class);
        assertThat(keysOf(json))
                .containsExactlyInAnyOrder("caseId", "evidenceVersion", "source", "retrievedAt", "payments");
        Map<?, ?> payment = (Map<?, ?>) ((List<?>) json.get("payments")).getFirst();
        assertThat(keysOf(payment))
                .containsExactlyInAnyOrder(
                        "paymentId",
                        "provided",
                        "orderRef",
                        "orderName",
                        "orderGroup",
                        "orderToPaymentSeconds",
                        "ordererProvided",
                        "ordererPhoneGroup",
                        "pickupDiffers");
        assertThat(payment.get("orderToPaymentSeconds")).isEqualTo(60);
    }

    // 사건에 묶인 결제의 주문 정보만 내준다
    @Test
    void orderContext_withOtherCasePayments_returnsOnlyCasePayments() {
        givenCase7WithPayments(3L, 4L, 5L);
        // P5에는 사건에 묶인 결제 ID만 넘긴다 — 다른 ID로 부르면 이 스텁이 맞지 않아 빈 결과가 된다
        when(paymentOrderReader.findOrders(List.of(3L, 4L, 5L)))
                .thenReturn(
                        List.of(order(3L, "order-a", 1), PaymentOrderSummary.notProvided(4L), order(5L, "order-a", 2)));
        when(paymentReader.findById(3L)).thenReturn(Optional.of(payment(3L)));
        when(paymentReader.findById(5L)).thenReturn(Optional.of(payment(5L)));

        OrderContextToolResponse response = finder().find(7L, NOW);

        assertThat(response.payments())
                .extracting(OrderContextToolResponse.Payment::paymentId)
                .containsExactly("3", "4", "5");
        // 같은 주문번호는 같은 주문 묶음, 세션이 없는 결제는 묶음 없이 "정보 없음"
        assertThat(response.payments())
                .extracting(OrderContextToolResponse.Payment::orderGroup)
                .containsExactly(1, null, 1);
        assertThat(response.payments().get(1).provided()).isFalse();
    }

    private static List<String> keysOf(Map<?, ?> json) {
        return json.keySet().stream().map(String::valueOf).toList();
    }

    private void givenCase7WithPayments(Long... paymentIds) {
        SecurityCase securityCase = SecurityCase.open(1L, "customer-1", CaseType.PAYMENT_FRAUD, NOW);
        ReflectionTestUtils.setField(securityCase, "id", 7L);
        when(securityCaseRepository.findById(7L)).thenReturn(Optional.of(securityCase));
        when(casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(7L))
                .thenReturn(List.of(paymentIds).stream()
                        .map(id -> CasePayment.link(securityCase, id, NOW))
                        .toList());
    }

    private OrderContextFinder finder() {
        return new OrderContextFinder(securityCaseRepository, casePaymentRepository, paymentOrderReader, paymentReader);
    }

    private static PaymentOrderSummary order(Long paymentId, String orderRef, int phoneGroup) {
        return PaymentOrderSummary.builder()
                .paymentId(paymentId)
                .provided(true)
                .orderRef(orderRef)
                .orderName("딸기 케이크")
                .orderCreatedAt(ORDERED_AT)
                .ordererProvided(true)
                .ordererPhoneGroup(phoneGroup)
                .pickupDiffers(false)
                .build();
    }

    private static PaymentSummary payment(Long paymentId) {
        return PaymentSummary.builder()
                .paymentId(paymentId)
                .amount(600_000L)
                .paymentStatus("HELD")
                .requestedAt(NOW)
                .build();
    }
}
