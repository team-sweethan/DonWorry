package com.donworry.investigation.report.service;

// AI 조사 긴급정지 기록이 저장됨 (ADR-0083 결정 5) — 진행 중 보고서를 고정 보고서로 마무리하게 알린다
// 스위치(AiSwitchService)가 AI 경로(AiInvestigationRunner)를 직접 부르면 순환이 생겨 같은 모듈 안의 동기 이벤트로 둔다
record AiSwitchStopped() {}
