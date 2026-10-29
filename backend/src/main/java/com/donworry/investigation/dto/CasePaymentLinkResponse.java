package com.donworry.investigation.dto;

// 결제 ID → 연결된 Case — Case에 연결된 결제만 응답에 담는다 (#309)
public record CasePaymentLinkResponse(Long paymentId, Long caseId, String caseStatus) {}
