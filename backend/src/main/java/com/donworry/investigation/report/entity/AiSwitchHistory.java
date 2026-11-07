package com.donworry.investigation.report.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// AI 조사 긴급정지 스위치의 변경 기록 (ADR-0083 결정 1) — 추가만 하고 고치지 않는다. 가이드라인 점검 항목 ⑧(사유와 함께 기록 · 보존)
@Entity
@Table(name = "ai_switch_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiSwitchHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "switch_history_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    private AiSwitchAction action;

    private String reason;

    // P1 platform_users 논리 참조 — 소유 담당이 달라 연관관계를 걸지 않는다
    private Long changedByUserId;

    // 그때의 이름 — 계정 이름이 바뀌어도 기록은 그대로
    private String changedByName;

    private Instant changedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private AiSwitchHistory(
            AiSwitchAction action, String reason, Long changedByUserId, String changedByName, Instant changedAt) {
        this.action = action;
        this.reason = reason;
        this.changedByUserId = changedByUserId;
        this.changedByName = changedByName;
        this.changedAt = changedAt;
    }

    public static AiSwitchHistory record(
            AiSwitchAction action, String reason, Long changedByUserId, String changedByName, Instant changedAt) {
        return builder()
                .action(action)
                .reason(reason)
                .changedByUserId(changedByUserId)
                .changedByName(changedByName)
                .changedAt(changedAt)
                .build();
    }

    public boolean isStopped() {
        return action == AiSwitchAction.STOPPED;
    }
}
