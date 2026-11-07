package com.donworry.investigation.report.dto;

import com.donworry.investigation.report.entity.AiSwitchHistory;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

// AI 조사 긴급정지 화면 (ADR-0083) — 설정 · 스위치 · 실제 적용과 최근 변경 기록(최신순)
// aiActive = configEnabled && switchOn. 설정이 꺼져 있으면 스위치를 켜도 고정 보고서다
@Builder
public record AiSwitchStatusResponse(
        boolean configEnabled, boolean switchOn, boolean aiActive, List<History> histories) {

    // ID는 P4 화면 응답 규칙대로 문자열 (ADR-0009 결정 4). 바꾼 직원 ID는 화면에 쓰지 않아 내보내지 않는다
    @Builder
    public record History(String historyId, String action, String reason, String changedByName, Instant changedAt) {

        public static History from(AiSwitchHistory history) {
            return History.builder()
                    .historyId(String.valueOf(history.getId()))
                    .action(history.getAction().name())
                    .reason(history.getReason())
                    .changedByName(history.getChangedByName())
                    .changedAt(history.getChangedAt())
                    .build();
        }
    }
}
