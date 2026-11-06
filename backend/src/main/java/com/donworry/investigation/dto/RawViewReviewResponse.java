package com.donworry.investigation.dto;

import java.time.Instant;
import lombok.Builder;

// ADMIN 원문 열람 검토 목록 한 줄 (#619) — 원문 · 가린 값 없음. 사건 상세도 열지 않는다 (ADR-0065)
@Builder
public record RawViewReviewResponse(
        Long reviewId,
        Long caseId,
        Long paymentId,
        Long merchantId,
        String merchantName,
        Long staffId,
        String staffName,
        String disputeRef,
        String merchantContactAttempt,
        String reason,
        Instant viewedAt,
        String status,
        String reviewedByName,
        Instant reviewedAt,
        String reviewMemo) {}
