package com.donworry.fds.dto;

import java.time.Instant;
import lombok.Builder;

// 경보 근거 접근 기록 한 줄 (#516) — 무엇을 · 어느 사건 · 결과만. 원문 · IP · 고객 번호 · 로그인 번호는 내보내지 않는다
@Builder
public record InsiderAlertEvidenceResponse(
        Long eventId,
        String accessType,
        String result,
        String denyReason,
        Long caseId,
        Long merchantId,
        String merchantName,
        Long paymentId,
        String fieldType,
        Instant occurredAt) {}
