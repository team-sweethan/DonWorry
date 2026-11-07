package com.donworry.identity.api;

import lombok.Builder;

// 판정으로 정지를 풀 때 남기는 내용 — 누가(관리자) · 어느 사건 · 무슨 판정 · 계정을 어디로
// caseId · decision은 P3 내부자 사건 번호 · 판정 코드라 identity는 값을 해석하지 않는다
@Builder
public record StaffLockRelease(Long staffId, Long adminId, Long caseId, String decision, StaffLockOutcome outcome) {}
