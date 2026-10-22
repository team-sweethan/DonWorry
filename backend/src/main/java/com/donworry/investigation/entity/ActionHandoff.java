package com.donworry.investigation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 판정별 금융 조치 인계 — 실제 CAPTURE · VOID 실행 결과는 P5 · P6가 관리한다
@Entity
@Table(name = "action_handoffs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActionHandoff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "handoff_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "decision_id")
    private PaymentDecision decision;

    @Enumerated(EnumType.STRING)
    private ActionType actionType;

    @Enumerated(EnumType.STRING)
    private HandoffStatus status;

    // 재시도해도 같은 값을 보내 담당 모듈이 중복 접수하지 않게 한다
    private UUID idempotencyKey;

    // 접수한 모듈의 작업 ID — 접수 확인 전에는 null
    private Long targetOperationId;

    private Instant requestedAt;

    private Instant lastAttemptedAt;

    private Instant acceptedAt;

    private String lastErrorCode;

    @Builder(access = AccessLevel.PRIVATE)
    private ActionHandoff(PaymentDecision decision, Instant requestedAt) {
        this.decision = Objects.requireNonNull(decision, "decision");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        this.actionType = decision.getDecision().actionType();
        this.status = HandoffStatus.REQUESTED;
        this.idempotencyKey = UUID.randomUUID();
        this.lastAttemptedAt = requestedAt;
    }

    // 조치 유형은 판정에서 정해진다 — ACCEPT는 CAPTURE, REJECT는 VOID
    public static ActionHandoff request(PaymentDecision decision, Instant now) {
        return ActionHandoff.builder().decision(decision).requestedAt(now).build();
    }

    // 담당 모듈의 영속 접수가 확인됐을 때만 호출한다. 이벤트 발행 사실만으로는 부르지 않는다
    public void accept(Long targetOperationId, Instant now) {
        requireNotAccepted();
        this.status = HandoffStatus.ACCEPTED;
        this.targetOperationId = Objects.requireNonNull(targetOperationId, "targetOperationId");
        this.acceptedAt = Objects.requireNonNull(now, "now");
        this.lastErrorCode = null;
    }

    // 거절 · 실패가 확인됐을 때만 호출한다. 타임아웃처럼 접수 여부를 모르면 REQUESTED로 둔다
    public void fail(String errorCode) {
        requireNotAccepted();
        this.status = HandoffStatus.FAILED;
        this.lastErrorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    // 같은 행 · 같은 멱등키로 다시 보낸다. requested_at은 최초 시각을 유지한다
    public void retry(Instant now) {
        requireNotAccepted();
        this.status = HandoffStatus.REQUESTED;
        this.lastAttemptedAt = Objects.requireNonNull(now, "now");
    }

    private void requireNotAccepted() {
        if (status == HandoffStatus.ACCEPTED) {
            throw new IllegalStateException("이미 접수된 인계는 바꿀 수 없습니다");
        }
    }
}
