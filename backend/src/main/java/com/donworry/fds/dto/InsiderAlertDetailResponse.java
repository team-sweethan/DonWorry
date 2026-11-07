package com.donworry.fds.dto;

import java.util.List;

// ADMIN 내부자 경보 상세 (#516) — 경보 + 근거 기록
public record InsiderAlertDetailResponse(InsiderAlertResponse alert, List<InsiderAlertEvidenceResponse> evidence) {}
