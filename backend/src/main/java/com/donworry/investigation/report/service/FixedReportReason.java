package com.donworry.investigation.report.service;

// 고정 보고서를 만든 이유 (ADR-0083 결정 6) — investigationTrace.fixedReason에 이름 그대로 남겨 화면이 구분한다
enum FixedReportReason {
    // 설정 ai.server.enabled 꺼짐 — 이 서버에 ai-server가 연결돼 있지 않다
    CONFIG_OFF("고정 문장 보고서입니다(AI 서버 꺼짐). FDS 점수는 부정거래 확률이 아닙니다."),
    // 관리자 화면의 AI 조사 긴급정지
    ADMIN_STOPPED("AI 조사 긴급정지 중에 만든 고정 문장 보고서입니다. FDS 점수는 부정거래 확률이 아닙니다.");

    final String limitation;

    FixedReportReason(String limitation) {
        this.limitation = limitation;
    }
}
