package com.donworry.investigation.repository;

// Case별 판정 건수 한 줄 — 목록이 Case마다 따로 세지 않게 한 번에 집계한다
public record CaseDecisionCount(Long caseId, long count) {}
