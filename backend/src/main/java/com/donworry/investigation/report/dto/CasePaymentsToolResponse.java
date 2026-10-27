package com.donworry.investigation.report.dto;

import java.time.Instant;
import java.util.List;

// AI Tool get_case_payments 응답 — Case에 묶인 결제의 P5 정보 (ADR-0009 Tool 6종)
// ID는 문자열(ADR-0009 결정 4). 결제 정보를 못 찾은 결제는 값이 null이고 missingPaymentIds에도 들어간다
public record CasePaymentsToolResponse(
        String caseId,
        int evidenceVersion,
        String source,
        Instant retrievedAt,
        List<Payment> payments,
        List<String> missingPaymentIds) {

    public record Payment(String paymentId, Long amount, String paymentStatus, Instant requestedAt) {}
}
