package com.donworry.investigation.repository;

import com.donworry.investigation.entity.CaseStatus;

// 결제가 연결된 Case 한 줄 — 경보 화면에서 Case로 이동하는 조회용 (#309)
public record CasePaymentLink(Long paymentId, Long caseId, CaseStatus caseStatus) {}
