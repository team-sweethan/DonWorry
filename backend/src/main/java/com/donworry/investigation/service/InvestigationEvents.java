package com.donworry.investigation.service;

// P3 내부 이벤트 — 커밋 뒤에 할 일(보고서 요청 · 금융 조치 인계)을 트랜잭션 밖으로 미루기 위해 쓴다
// 다른 모듈에 공개하지 않는다
final class InvestigationEvents {

    private InvestigationEvents() {}

    // Case가 생기거나 결제가 병합돼 증거 버전이 바뀜 → 커밋 후 보고서 요청
    record CaseEvidenceChanged(Long caseId, int evidenceVersion) {}

    // 판정과 인계 REQUESTED가 저장됨 → 커밋 후 P5에 금융 조치 요청
    record HandoffRequested(Long handoffId) {}
}
