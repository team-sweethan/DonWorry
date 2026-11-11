package com.donworry.investigation.report.service;

import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.dto.OrderContextToolResponse;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.InvestigationErrorCode;
import com.donworry.investigation.service.InvestigationException;
import com.donworry.payment.api.PaymentOrderReader;
import com.donworry.payment.api.PaymentOrderSummary;
import com.donworry.payment.api.PaymentReader;
import com.donworry.payment.api.PaymentSummary;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 사건 결제의 주문 정보 (#634) — AI Tool과 AI를 끈 고정 보고서가 같이 쓴다
// CakeShop을 HTTP로 부르지 않는다: 가맹점이 결제 준비 때 보낸 값이 이미 P5에 있다 (ADR-0009 §5.7 개정)
// 사건에 묶인 결제만 P5에 넘긴다 — 다른 사건 · 다른 고객 주문은 읽지 않는다
@Component
@RequiredArgsConstructor
public class OrderContextFinder {

    static final String SOURCE = "P5";

    private final SecurityCaseRepository securityCaseRepository;
    private final CasePaymentRepository casePaymentRepository;
    private final PaymentOrderReader paymentOrderReader;
    private final PaymentReader paymentReader;

    // 별도 읽기 트랜잭션 — 고정 보고서가 이 조회 실패를 잡아 넘겨도 보고서 트랜잭션이 되돌림 전용이 되지 않게 한다
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public OrderContextToolResponse find(Long caseId, Instant now) {
        SecurityCase securityCase = securityCaseRepository
                .findById(caseId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.CASE_NOT_FOUND));
        List<Long> casePaymentIds = casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(caseId).stream()
                .map(CasePayment::getPaymentId)
                .toList();
        List<PaymentOrderSummary> orders = paymentOrderReader.findOrders(casePaymentIds);

        // 사건에 묶인 순서대로 처음 보는 주문번호에 1, 2, 3…
        Map<String, Integer> orderGroups = new HashMap<>();
        List<OrderContextToolResponse.Payment> payments = orders.stream()
                .map(order -> order.provided()
                        ? OrderContextToolResponse.Payment.builder()
                                .paymentId(String.valueOf(order.paymentId()))
                                .provided(true)
                                .orderRef(order.orderRef())
                                .orderName(order.orderName())
                                .orderGroup(
                                        orderGroups.computeIfAbsent(order.orderRef(), ref -> orderGroups.size() + 1))
                                .orderToPaymentSeconds(secondsToPayment(order))
                                .ordererProvided(order.ordererProvided())
                                .ordererPhoneGroup(order.ordererPhoneGroup())
                                .pickupDiffers(order.pickupDiffers())
                                .build()
                        : OrderContextToolResponse.Payment.builder()
                                .paymentId(String.valueOf(order.paymentId()))
                                .build())
                .toList();
        return OrderContextToolResponse.builder()
                .caseId(String.valueOf(caseId))
                .evidenceVersion(securityCase.getEvidenceVersion())
                .source(SOURCE)
                .retrievedAt(now)
                .payments(payments)
                .build();
    }

    // 결제 요청 시각은 P5 결제 저장 시각이다 (PaymentSummary.requestedAt). 어느 쪽이든 없으면 null
    private Long secondsToPayment(PaymentOrderSummary order) {
        if (order.orderCreatedAt() == null) {
            return null;
        }
        return paymentReader
                .findById(order.paymentId())
                .map(PaymentSummary::requestedAt)
                .map(requestedAt ->
                        Duration.between(order.orderCreatedAt(), requestedAt).getSeconds())
                .orElse(null);
    }
}
