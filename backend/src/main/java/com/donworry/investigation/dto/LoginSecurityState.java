package com.donworry.investigation.dto;

// 로그인 보안 대시보드 상태 띠 (#770)
// UNDER_ATTACK = 탐지 기준(시도 수 · 실패 비율) 모두 넘음 — 이때 처음 보는 브라우저로 성공한 보안 직원은 SEC-INS-10
// CAUTION = 실패 비율은 넘었고 시도가 기준의 절반 이상 · 기준 미만 (화면 안내용, 탐지에는 쓰지 않음)
public enum LoginSecurityState {
    NORMAL,
    CAUTION,
    UNDER_ATTACK
}
