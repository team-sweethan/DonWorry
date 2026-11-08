package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.donworry.fds.api.InsiderAlertRaised;
import com.donworry.fds.api.InsiderAlertSeverity;
import com.donworry.fds.api.InsiderRule;
import com.donworry.fds.api.StaffCaseScopeReader;
import com.donworry.fds.entity.InsiderAlert;
import com.donworry.fds.entity.InsiderAlertStatus;
import com.donworry.fds.repository.InsiderAlertEvidenceRepository;
import com.donworry.fds.repository.InsiderAlertRepository;
import com.donworry.identity.api.StaffAccessReader;
import com.donworry.identity.api.StaffAccountLocker;
import com.donworry.identity.api.StaffLockReason;
import com.donworry.identity.api.StaffLoginReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

// 내보내기 문지기 INS-05 (#651) — 승인된 테스트 목록만 (노트 issue-651 "승인된 테스트 목록")
// 정지 · 경보가 응답 롤백 뒤에도 남는지는 PaymentTransactionsHttpMariaDbTests가 실제 DB로 확인한다
class InsiderExportGuardTests {
    private static final Long STAFF = 4L;
    private static final Instant T0 = Instant.parse("2026-11-08T01:00:00Z");

    private final InsiderAlertRepository alerts = mock(InsiderAlertRepository.class);
    private final InsiderAlertEvidenceRepository evidence = mock(InsiderAlertEvidenceRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final StaffAccountLocker locker = mock(StaffAccountLocker.class);
    // 경보 저장은 실제 탐지 서비스로 — INS-05 등급 · 근거 · 이벤트가 실제 규칙대로 만들어지는지 본다
    private final InsiderDetectionService detection = new InsiderDetectionService(
            alerts,
            evidence,
            mock(StaffAccessReader.class),
            publisher,
            InsiderDetectionProperties.builder().build(),
            mock(StaffLoginReader.class),
            mock(StaffCaseScopeReader.class),
            locker);
    private final InsiderExportGuardService guard = new InsiderExportGuardService(detection, locker);

    // 이 직원에게 열려 있는 경보 — 등급 조건 조회도 실제 쿼리처럼 이 목록에서 답한다
    private final List<InsiderAlert> open = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(alerts.findByStaffIdAndStatus(STAFF, InsiderAlertStatus.OPEN)).thenAnswer(call -> List.copyOf(open));
        when(alerts.existsByStaffIdAndStatusAndSeverityIn(eq(STAFF), eq(InsiderAlertStatus.OPEN), any()))
                .thenAnswer(call -> {
                    Collection<InsiderAlertSeverity> severities = call.getArgument(2);
                    return open.stream().anyMatch(a -> severities.contains(a.getSeverity()));
                });
        when(alerts.findByStaffIdAndRuleCodeAndStatus(STAFF, InsiderRule.INS_05, InsiderAlertStatus.OPEN))
                .thenReturn(Optional.empty());
    }

    // 높음 경보가 열려 있으면 INS-05 위험 경보(이벤트 발행) → 계정 정지(근거 경보 번호) → 차단
    // (INS-08이 3,000행으로 높음이 된 뒤의 다음 내보내기)
    @Test
    void allowExport_whenHighAlertOpen_raisesIns05AndLocksAndBlocks() {
        open.add(alert(10L, InsiderRule.INS_08, InsiderAlertSeverity.HIGH));
        when(evidence.findAccessEventIdsOf(List.of(10L))).thenReturn(List.of(101L, 102L));
        savedAs(20L);

        assertThat(guard.allowExport(STAFF)).isFalse();

        var saved = ArgumentCaptor.forClass(InsiderAlert.class);
        verify(alerts).save(saved.capture());
        assertThat(saved.getValue().getRuleCode()).isEqualTo(InsiderRule.INS_05);
        assertThat(saved.getValue().getSeverity()).isEqualTo(InsiderAlertSeverity.CRITICAL);
        assertThat(saved.getValue().getSummary()).contains("INS-08");
        verify(evidence, times(2)).save(any());
        var raised = ArgumentCaptor.forClass(InsiderAlertRaised.class);
        verify(publisher).publishEvent(raised.capture());
        assertThat(raised.getValue().rule()).isEqualTo(InsiderRule.INS_05);
        assertThat(raised.getValue().severity()).isEqualTo(InsiderAlertSeverity.CRITICAL);
        verify(locker).lockBySystem(STAFF, StaffLockReason.INSIDER_EXPORT_BLOCKED, 20L);
    }

    // 열린 경보가 없으면 정지 · 경보 없이 통과한다
    @Test
    void allowExport_whenNoOpenAlert_allowsWithoutLock() {
        assertThat(guard.allowExport(STAFF)).isTrue();

        verify(locker, never()).lockBySystem(any(), any(), any());
        verify(alerts, never()).save(any());
    }

    // 주의 경보만 열려 있으면(예: 주간 보고용 전체 내보내기로 INS-08 주의) 정지 · 경보 없이 통과한다 — 정상 업무를 막지 않는다
    @Test
    void allowExport_whenOnlyCautionAlertsOpen_allowsWithoutLock() {
        open.add(alert(10L, InsiderRule.INS_08, InsiderAlertSeverity.CAUTION));
        open.add(alert(11L, InsiderRule.INS_03, InsiderAlertSeverity.CAUTION));

        assertThat(guard.allowExport(STAFF)).isTrue();

        verify(locker, never()).lockBySystem(any(), any(), any());
        verify(alerts, never()).save(any());
    }

    // 경보 저장이 실패해도 계정은 정지하고(근거 경보 번호 없이) 차단한다
    @Test
    void allowExport_whenAlertSaveFails_stillLocksAndBlocks() {
        open.add(alert(10L, InsiderRule.INS_07, InsiderAlertSeverity.HIGH));
        when(evidence.findAccessEventIdsOf(anyList())).thenReturn(List.of(101L));
        when(alerts.save(any())).thenThrow(new IllegalStateException("저장 실패"));

        assertThat(guard.allowExport(STAFF)).isFalse();

        verify(locker).lockBySystem(eq(STAFF), eq(StaffLockReason.INSIDER_EXPORT_BLOCKED), isNull());
    }

    // 계정 정지가 실패해도 내보내기는 차단한다
    @Test
    void allowExport_whenLockFails_stillBlocks() {
        open.add(alert(10L, InsiderRule.INS_07, InsiderAlertSeverity.HIGH));
        when(evidence.findAccessEventIdsOf(anyList())).thenReturn(List.of(101L));
        savedAs(20L);
        doThrow(new IllegalStateException("정지 실패")).when(locker).lockBySystem(any(), any(), any());

        assertThat(guard.allowExport(STAFF)).isFalse();
    }

    // 계정 정지는 경보 저장과 같은 직원 잠금 안에서 끝난다 (#670 Codex 리뷰) — 잠금을 놓은 뒤 따로 정지하면
    // 그 사이 끝난 내부자 사건 판정(경보 닫기 · 정지 해제) 뒤에 정지가 걸려 아무도 풀 수 없게 된다
    @Test
    void raiseExportBlock_blockingAlertOpen_locksAccountBeforeCommit() {
        open.add(alert(10L, InsiderRule.INS_08, InsiderAlertSeverity.HIGH));
        when(evidence.findAccessEventIdsOf(List.of(10L))).thenReturn(List.of(101L));
        savedAs(20L);

        Long alertId = detection.raiseExportBlock(STAFF, T0);

        assertThat(alertId).isEqualTo(20L);
        var order = inOrder(alerts, locker);
        order.verify(alerts).lockDetectionState(STAFF);
        order.verify(locker).lockBySystem(STAFF, StaffLockReason.INSIDER_EXPORT_BLOCKED, 20L);
    }

    private void savedAs(Long id) {
        when(alerts.save(any())).thenAnswer(call -> {
            InsiderAlert saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", id);
            return saved;
        });
    }

    private static InsiderAlert alert(Long id, InsiderRule rule, InsiderAlertSeverity severity) {
        InsiderAlert alert = InsiderAlert.open(STAFF, rule, severity, "테스트", T0, T0, T0);
        ReflectionTestUtils.setField(alert, "id", id);
        return alert;
    }
}
