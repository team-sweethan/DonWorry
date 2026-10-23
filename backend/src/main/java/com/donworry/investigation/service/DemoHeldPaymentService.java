package com.donworry.investigation.service;

import com.donworry.investigation.dto.DemoHeldPaymentRequest;
import com.donworry.investigation.dto.DemoHeldPaymentResponse;
import com.donworry.investigation.service.PaymentInfoReader.PaymentInfo;
import com.donworry.investigation.service.mock.MockPaymentInfoStore;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 시연용 — P5 HELD 이벤트 대신 목 결제를 만들어 Case 접수로 넘긴다. P5 연동 후 지운다
// 트랜잭션을 걸지 않는다 — CaseIntakeService 커밋 뒤에 보고서 요청이 돌게 하려고
@Service
@RequiredArgsConstructor
public class DemoHeldPaymentService {

    private static final List<String> DEFAULT_RULE_HITS = List.of("NEW_CARD", "HIGH_AMOUNT");

    private final MockPaymentInfoStore mockPaymentInfoStore;
    private final CaseIntakeService caseIntakeService;

    public DemoHeldPaymentResponse create(DemoHeldPaymentRequest request) {
        Instant now = Instant.now();
        List<String> ruleHits =
                request.ruleHits() == null || request.ruleHits().isEmpty() ? DEFAULT_RULE_HITS : request.ruleHits();
        PaymentInfo payment = mockPaymentInfoStore.registerHeld(request.amount(), request.riskScore(), ruleHits, now);
        Long caseId = caseIntakeService.receive(new HeldPayment(
                payment.paymentId(),
                request.merchantId(),
                request.customerRef(),
                request.amount(),
                "MOCK-AUTH-" + payment.paymentId(),
                null,
                now));
        return new DemoHeldPaymentResponse(payment.paymentId(), caseId);
    }
}
