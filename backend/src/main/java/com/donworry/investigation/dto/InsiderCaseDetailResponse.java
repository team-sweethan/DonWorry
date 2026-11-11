package com.donworry.investigation.dto;

import java.util.List;
import lombok.Builder;

// ADMIN 내부자 사건 상세 (#670) — 묶인 경보 · 지금 계정 정지 여부 · 남은 FDS 사건 수 · 이 직원의 지난 판정
@Builder
public record InsiderCaseDetailResponse(
        InsiderCaseResponse insiderCase,
        List<InsiderCaseAlertResponse> alerts,
        boolean accountLocked,
        long openFdsCaseCount,
        List<InsiderCaseResponse> pastCases) {}
