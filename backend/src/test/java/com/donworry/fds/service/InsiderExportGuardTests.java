package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
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
import java.util.List;
import java.util.Optional;
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
            mock(StaffCaseScopeReader.class));
    private final InsiderExportGuardService guard = new InsiderExportGuardService(detection, locker);

    // 열린 경보가 있으면 INS-05 위험 경보(이벤트 발행) → 계정 정지(근거 경보 번호) → 차단
    @Test
    void allowExport_whenOpenAlertExists_raisesIns05AndLocksAndBlocks() {
        InsiderAlert open = openAlert(10L, InsiderRule.INS_03);
        when(alerts.existsByStaffIdAndStatus(STAFF, InsiderAlertStatus.OPEN)).thenReturn(true);
        when(alerts.findByStaffIdAndStatus(STAFF, InsiderAlertStatus.OPEN)).thenReturn(List.of(open));
        when(evidence.findAccessEventIdsOf(List.of(10L))).thenReturn(List.of(101L, 102L));
        when(alerts.findByStaffIdAndRuleCodeAndStatus(STAFF, InsiderRule.INS_05, InsiderAlertStatus.OPEN))
                .thenReturn(Optional.empty());
        when(alerts.save(any())).thenAnswer(call -> {
            InsiderAlert saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 20L);
            return saved;
        });

        assertThat(guard.allowExport(STAFF)).isFalse();

        var saved = ArgumentCaptor.forClass(InsiderAlert.class);
        verify(alerts).save(saved.capture());
        assertThat(saved.getValue().getRuleCode()).isEqualTo(InsiderRule.INS_05);
        assertThat(saved.getValue().getSeverity()).isEqualTo(InsiderAlertSeverity.CRITICAL);
        assertThat(saved.getValue().getSummary()).contains("INS-03");
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
        when(alerts.existsByStaffIdAndStatus(STAFF, InsiderAlertStatus.OPEN)).thenReturn(false);

        assertThat(guard.allowExport(STAFF)).isTrue();

        verify(locker, never()).lockBySystem(any(), any(), any());
        verify(alerts, never()).save(any());
    }

    // 경보 저장이 실패해도 계정은 정지하고(근거 경보 번호 없이) 차단한다
    @Test
    void allowExport_whenAlertSaveFails_stillLocksAndBlocks() {
        when(alerts.existsByStaffIdAndStatus(STAFF, InsiderAlertStatus.OPEN)).thenReturn(true);
        when(alerts.findByStaffIdAndStatus(STAFF, InsiderAlertStatus.OPEN))
                .thenReturn(List.of(openAlert(10L, InsiderRule.INS_03)));
        when(evidence.findAccessEventIdsOf(anyList())).thenReturn(List.of(101L));
        when(alerts.findByStaffIdAndRuleCodeAndStatus(STAFF, InsiderRule.INS_05, InsiderAlertStatus.OPEN))
                .thenReturn(Optional.empty());
        when(alerts.save(any())).thenThrow(new IllegalStateException("저장 실패"));

        assertThat(guard.allowExport(STAFF)).isFalse();

        verify(locker).lockBySystem(eq(STAFF), eq(StaffLockReason.INSIDER_EXPORT_BLOCKED), isNull());
    }

    // 계정 정지가 실패해도 내보내기는 차단한다
    @Test
    void allowExport_whenLockFails_stillBlocks() {
        when(alerts.existsByStaffIdAndStatus(STAFF, InsiderAlertStatus.OPEN)).thenReturn(true);
        when(alerts.findByStaffIdAndStatus(STAFF, InsiderAlertStatus.OPEN))
                .thenReturn(List.of(openAlert(10L, InsiderRule.INS_03)));
        when(evidence.findAccessEventIdsOf(anyList())).thenReturn(List.of(101L));
        when(alerts.findByStaffIdAndRuleCodeAndStatus(STAFF, InsiderRule.INS_05, InsiderAlertStatus.OPEN))
                .thenReturn(Optional.empty());
        when(alerts.save(any())).thenAnswer(call -> {
            InsiderAlert saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 20L);
            return saved;
        });
        doThrow(new IllegalStateException("정지 실패")).when(locker).lockBySystem(any(), any(), any());

        assertThat(guard.allowExport(STAFF)).isFalse();
    }

    private static InsiderAlert openAlert(Long id, InsiderRule rule) {
        InsiderAlert alert = InsiderAlert.open(STAFF, rule, InsiderAlertSeverity.CAUTION, "테스트", T0, T0, T0);
        ReflectionTestUtils.setField(alert, "id", id);
        return alert;
    }
}
