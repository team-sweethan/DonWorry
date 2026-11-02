package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.donworry.investigation.entity.CaseStatus;
import com.donworry.investigation.entity.HandoffStatus;
import com.donworry.investigation.service.InvestigationRules.CaseSnapshot;
import com.donworry.investigation.service.InvestigationRules.ReportProgress;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class InvestigationRulesTests {

    static final Instant CREATED_AT = Instant.parse("2026-10-22T05:00:00Z");

    // 병합 가능 상태 집합은 밖에서 바꿀 수 없다
    @Test
    void mergeableStatuses_isUnmodifiable() {
        assertThatThrownBy(() -> InvestigationRules.MERGEABLE_STATUSES.add(CaseStatus.CLOSED))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // 판정 전 Case는 생성 후 30분 미만까지 결제를 더 받는다
    @Test
    void canMerge_withinWindowAndNoDecision_returnsTrue() {
        Instant now = CREATED_AT.plusSeconds(29 * 60 + 59);

        assertThat(InvestigationRules.canMerge(CaseStatus.INVESTIGATING, CREATED_AT, 0, now))
                .isTrue();
    }

    // 생성 후 정확히 30분이 되면 병합 기한이 끝난다
    @Test
    void canMerge_atWindowBoundary_returnsFalse() {
        Instant now = CREATED_AT.plus(InvestigationRules.MERGE_WINDOW);

        assertThat(InvestigationRules.canMerge(CaseStatus.INVESTIGATING, CREATED_AT, 0, now))
                .isFalse();
    }

    // 판정이 하나라도 있으면 기한 안이어도 결제 목록이 고정된다
    @Test
    void canMerge_afterAnyDecision_returnsFalse() {
        Instant now = CREATED_AT.plusSeconds(60);

        assertThat(InvestigationRules.canMerge(CaseStatus.DECISION_PENDING, CREATED_AT, 1, now))
                .isFalse();
    }

    // 판정 이후 단계 상태의 Case에는 병합하지 않는다
    @ParameterizedTest
    @EnumSource(
            value = CaseStatus.class,
            names = {"ACTION_PENDING", "ACTION_FAILED", "CLOSED"})
    void canMerge_whenPastDecisionStage_returnsFalse(CaseStatus status) {
        Instant now = CREATED_AT.plusSeconds(60);

        assertThat(InvestigationRules.canMerge(status, CREATED_AT, 0, now)).isFalse();
    }

    // 보고서가 아직 요청되지 않은 새 Case는 OPEN이다
    @Test
    void nextStatus_withoutReport_returnsOpen() {
        var snapshot = new CaseSnapshot(CaseStatus.OPEN, 1, 0, List.of(), ReportProgress.NONE);

        assertThat(InvestigationRules.nextStatus(snapshot)).isEqualTo(CaseStatus.OPEN);
    }

    // 최신 보고서를 만드는 중이면 조사 중이다
    @Test
    void nextStatus_whileReportInProgress_returnsInvestigating() {
        var snapshot = new CaseSnapshot(CaseStatus.OPEN, 2, 0, List.of(), ReportProgress.IN_PROGRESS);

        assertThat(InvestigationRules.nextStatus(snapshot)).isEqualTo(CaseStatus.INVESTIGATING);
    }

    // 보고서가 끝났고 판정할 결제가 남아 있으면 판정 대기다
    @Test
    void nextStatus_reportCompletedAndUndecidedPayment_returnsDecisionPending() {
        var snapshot = new CaseSnapshot(
                CaseStatus.INVESTIGATING, 2, 1, List.of(HandoffStatus.ACCEPTED), ReportProgress.COMPLETED);

        assertThat(InvestigationRules.nextStatus(snapshot)).isEqualTo(CaseStatus.DECISION_PENDING);
    }

    // 모든 결제를 판정했지만 접수 확인이 남았으면 인계 대기다
    @Test
    void nextStatus_allDecidedAndHandoffRequested_returnsActionPending() {
        var snapshot = new CaseSnapshot(
                CaseStatus.DECISION_PENDING,
                2,
                2,
                List.of(HandoffStatus.ACCEPTED, HandoffStatus.REQUESTED),
                ReportProgress.COMPLETED);

        assertThat(InvestigationRules.nextStatus(snapshot)).isEqualTo(CaseStatus.ACTION_PENDING);
    }

    // 모든 결제의 인계가 접수되면 종료한다 — 실제 금융 실행 성공은 조건이 아니다
    @Test
    void nextStatus_allHandoffsAccepted_returnsClosed() {
        var snapshot = new CaseSnapshot(
                CaseStatus.ACTION_PENDING,
                2,
                2,
                List.of(HandoffStatus.ACCEPTED, HandoffStatus.ACCEPTED),
                ReportProgress.COMPLETED);

        assertThat(InvestigationRules.nextStatus(snapshot)).isEqualTo(CaseStatus.CLOSED);
    }

    // 인계 실패가 하나라도 있으면 다른 인계가 접수됐어도 종료하지 않는다
    @Test
    void nextStatus_anyHandoffFailed_returnsActionFailed() {
        var snapshot = new CaseSnapshot(
                CaseStatus.ACTION_PENDING,
                2,
                2,
                List.of(HandoffStatus.ACCEPTED, HandoffStatus.FAILED),
                ReportProgress.COMPLETED);

        assertThat(InvestigationRules.nextStatus(snapshot)).isEqualTo(CaseStatus.ACTION_FAILED);
    }

    // 실패한 인계를 재시도해 다시 요청 상태가 되면 인계 대기로 돌아간다
    @Test
    void nextStatus_failedHandoffRetried_returnsActionPending() {
        var snapshot = new CaseSnapshot(
                CaseStatus.ACTION_FAILED, 1, 1, List.of(HandoffStatus.REQUESTED), ReportProgress.COMPLETED);

        assertThat(InvestigationRules.nextStatus(snapshot)).isEqualTo(CaseStatus.ACTION_PENDING);
    }

    // 종료된 Case는 이후 어떤 값이 와도 다시 열지 않는다
    @Test
    void nextStatus_whenClosed_staysClosed() {
        var snapshot =
                new CaseSnapshot(CaseStatus.CLOSED, 1, 1, List.of(HandoffStatus.FAILED), ReportProgress.COMPLETED);

        assertThat(InvestigationRules.nextStatus(snapshot)).isEqualTo(CaseStatus.CLOSED);
    }

    // 결제가 없거나 판정 · 인계 수가 맞지 않는 요약은 받지 않는다
    @Test
    void caseSnapshot_withInconsistentCounts_throwsException() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CaseSnapshot(CaseStatus.OPEN, 0, 0, List.of(), ReportProgress.NONE));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CaseSnapshot(CaseStatus.OPEN, 1, 2, List.of(), ReportProgress.NONE));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CaseSnapshot(
                        CaseStatus.OPEN,
                        2,
                        1,
                        List.of(HandoffStatus.REQUESTED, HandoffStatus.REQUESTED),
                        ReportProgress.COMPLETED));
    }
}
