package com.donworry.investigation.repository;

import com.donworry.investigation.entity.CaseStatus;

// 상태별 Case 건수 한 줄 — 사이드바 배지용 집계 (#224)
public record CaseStatusCount(CaseStatus status, long count) {}
