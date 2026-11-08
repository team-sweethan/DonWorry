package com.donworry.investigation.service;

import java.util.Objects;

// 내부자 사건이 열렸거나 열린 사건에 차단(INS-05) 경보가 붙었다는 모듈 안 알림 (#675, ADR-0082 조각 9)
// P4 해제 검토 보고서가 커밋 뒤에 받아 새 판을 만든다. 같은 사건 · 같은 이유가 다시 와도 보고서는 한 판
// alertId는 이 변화를 만든 경보(P2 insider_alerts 번호)
public record InsiderCaseChanged(Long insiderCaseId, Reason reason, Long alertId) {

    public enum Reason {
        // 높음 이상 경보로 사건이 새로 열림 (차단 경보로 바로 열리면 EXPORT_BLOCKED)
        OPENED,
        // 높음 이상 경보 중 내보내기 시도가 막히고 계정이 정지됨
        EXPORT_BLOCKED
    }

    public InsiderCaseChanged {
        Objects.requireNonNull(insiderCaseId, "insiderCaseId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(alertId, "alertId");
    }
}
