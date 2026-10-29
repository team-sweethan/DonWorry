package com.donworry.investigation.dto;

import java.time.Instant;

// 조치 내역 한 줄 — 화면 HandoffListItem과 같은 모양 (#314)
public record HandoffListItemResponse(
        Long handoffId,
        Long caseId,
        Long paymentId,
        // 결제 정보를 찾지 못하면 null — 서버 재시작으로 시연 결제 목업이 비었을 때
        Long amount,
        String decision,
        String actionType,
        String status,
        String lastErrorCode,
        Long staffId,
        Instant decidedAt,
        Instant requestedAt,
        // P3가 완료를 반영한 시각 (ADR-0019 acceptedAt). 실제 완료 시각 completedAt은 Case 이력에 있다
        Instant acceptedAt) {}
