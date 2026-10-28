package com.donworry.investigation.dto;

import java.util.Map;

// 상태별 Case 건수 — 모든 상태 이름이 키로 있고, 없는 상태는 0이다 (#224)
public record CaseCountsResponse(Map<String, Long> statusCounts) {}
