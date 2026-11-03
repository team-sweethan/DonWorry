package com.donworry.investigation.report.dto;

import java.time.Instant;
import java.util.List;

// AI Tool get_payment_history 응답 — 같은 가맹점 · 같은 고객의 최근 결제, 이 사건 결제는 뺀다 (#338, ADR-0009)
// statusCounts는 payments(최대 20건) 안에서 센 값이다. 기간 안에 더 있으면 truncated=true
public record PaymentHistoryToolResponse(
        String caseId,
        int evidenceVersion,
        String source,
        Instant retrievedAt,
        int lookbackDays,
        List<Payment> payments,
        List<StatusCount> statusCounts,
        boolean truncated) {

    public record Payment(String paymentId, Long amount, String paymentStatus, Instant requestedAt) {}

    public record StatusCount(String paymentStatus, int count) {}
}
