package com.donworry.investigation.service;

import com.donworry.investigation.entity.CaseStatus;
import com.donworry.investigation.entity.HandoffStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

// P3 내부의 순수 판단 규칙. 저장 · 다른 모듈 호출 · 이력 기록은 호출자가 담당한다
public final class InvestigationRules {

    // 병합 기한은 최초 created_at 기준이며 연장하지 않는다 (fds 정본 03 §3.9 ③)
    public static final Duration MERGE_WINDOW = Duration.ofMinutes(30);

    // 판정 전 단계만 결제를 더 받는다. 판정이 하나라도 있으면 대상 목록이 고정된다 (fds 정본 03 §3.5)
    // 밖에서 바꾸면 모든 병합 판단이 달라지므로 변경 불가 집합으로 둔다
    public static final Set<CaseStatus> MERGEABLE_STATUSES =
            Set.of(CaseStatus.OPEN, CaseStatus.INVESTIGATING, CaseStatus.DECISION_PENDING);

    private InvestigationRules() {}

    // P4 보고서 상태를 P3 판단에 필요한 만큼만 줄인 값 — P4 상태값에 직접 의존하지 않는다
    public enum ReportProgress {
        NONE,
        IN_PROGRESS,
        COMPLETED
    }

    // 상태 결정에 필요한 Case 요약. handoffs는 판정된 결제의 인계 상태 목록이다
    public record CaseSnapshot(
            CaseStatus current,
            int paymentCount,
            int decidedCount,
            List<HandoffStatus> handoffs,
            ReportProgress latestReport) {
        public CaseSnapshot {
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(latestReport, "latestReport");
            handoffs = List.copyOf(Objects.requireNonNull(handoffs, "handoffs"));
            if (paymentCount < 1) {
                throw new IllegalArgumentException("Case에는 결제가 한 건 이상 있어야 합니다");
            }
            if (decidedCount < 0 || decidedCount > paymentCount) {
                throw new IllegalArgumentException("판정 수는 0 이상 결제 수 이하여야 합니다");
            }
            if (handoffs.size() > decidedCount) {
                throw new IllegalArgumentException("인계는 판정 수보다 많을 수 없습니다");
            }
        }
    }

    public static Instant mergeDeadline(Instant createdAt) {
        return Objects.requireNonNull(createdAt, "createdAt").plus(MERGE_WINDOW);
    }

    // 같은 가맹점 · 고객 · 유형인지는 조회 조건으로 거른다. 여기서는 시간 · 상태 · 판정만 본다
    public static boolean canMerge(CaseStatus status, Instant createdAt, long decidedCount, Instant now) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(now, "now");
        return MERGEABLE_STATUSES.contains(status) && decidedCount == 0 && now.isBefore(mergeDeadline(createdAt));
    }

    // 우선순위: 종료 유지 > 인계 실패 > 모두 접수 > 모두 판정 > 보고서 생성 중 > 보고서 없음 > 판정 대기
    public static CaseStatus nextStatus(CaseSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.current() == CaseStatus.CLOSED) {
            // 종료 뒤의 금융 실행 결과는 Case를 다시 열지 않는다 (fds 정본 03 §3.3)
            return CaseStatus.CLOSED;
        }
        if (snapshot.handoffs().contains(HandoffStatus.FAILED)) {
            return CaseStatus.ACTION_FAILED;
        }
        boolean allDecided = snapshot.decidedCount() == snapshot.paymentCount();
        boolean allAccepted = snapshot.handoffs().size() == snapshot.paymentCount()
                && snapshot.handoffs().stream().allMatch(status -> status == HandoffStatus.ACCEPTED);
        if (allDecided && allAccepted) {
            return CaseStatus.CLOSED;
        }
        if (allDecided) {
            return CaseStatus.ACTION_PENDING;
        }
        return switch (snapshot.latestReport()) {
            case IN_PROGRESS -> CaseStatus.INVESTIGATING;
            case NONE -> CaseStatus.OPEN;
            case COMPLETED -> CaseStatus.DECISION_PENDING;
        };
    }
}
