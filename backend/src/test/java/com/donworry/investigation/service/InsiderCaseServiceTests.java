package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.donworry.fds.api.InsiderAlertCloser;
import com.donworry.fds.api.InsiderAlertRaised;
import com.donworry.fds.api.InsiderAlertReader;
import com.donworry.fds.api.InsiderAlertSeverity;
import com.donworry.fds.api.InsiderRule;
import com.donworry.identity.api.SecurityStaffDirectory;
import com.donworry.identity.api.StaffAccountLocker;
import com.donworry.identity.api.StaffLockOutcome;
import com.donworry.identity.api.StaffLockReason;
import com.donworry.identity.api.StaffLockRelease;
import com.donworry.identity.api.StaffSummary;
import com.donworry.investigation.dto.InsiderCaseDecisionRequest;
import com.donworry.investigation.dto.InsiderLockReleaseRequest;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.InsiderCase;
import com.donworry.investigation.entity.InsiderCaseAlert;
import com.donworry.investigation.entity.InsiderCaseDecision;
import com.donworry.investigation.entity.InsiderCaseStatus;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.InsiderCaseAlertRepository;
import com.donworry.investigation.repository.InsiderCaseRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

// 내부자 사건 (#670) — 경보 묶기 · 판정 3개 · 징계 사건 정지 해제 + 해제 검토 보고서 알림(#675). P2 경보 · P1 정지는 공개 창구 대역으로 정한다
@ExtendWith(MockitoExtension.class)
class InsiderCaseServiceTests {

    private static final Long STAFF_ID = 21L;
    private static final Long CASE_ID = 10L;
    private static final Instant T0 = Instant.parse("2026-11-08T01:00:00Z");
    private static final StaffSummary ADMIN =
            StaffSummary.builder().userId(1L).displayName("관리자").role("ADMIN").build();

    @Mock
    InsiderCaseRepository cases;

    @Mock
    InsiderCaseAlertRepository links;

    @Mock
    SecurityCaseRepository securityCases;

    @Mock
    CaseAssignmentService caseAssignmentService;

    @Mock
    SecurityStaffDirectory staffDirectory;

    @Mock
    SecurityStaffGuard guard;

    @Mock
    InsiderAlertReader alertReader;

    @Mock
    InsiderAlertCloser alertCloser;

    @Mock
    StaffAccountLocker locker;

    @Mock
    ApplicationEventPublisher events;

    @InjectMocks
    InsiderCaseService service;

    // 열린 사건이 없는 직원의 높음 경보는 새 사건을 열고 그 경보를 묶는다
    @Test
    void alertRaised_noOpenCase_createsCaseAndLinksAlert() {
        when(links.findById(500L)).thenReturn(Optional.empty());
        when(alertReader.isOpen(500L)).thenReturn(true);
        when(cases.findByStaffIdAndStatus(STAFF_ID, InsiderCaseStatus.OPEN)).thenReturn(Optional.empty());
        when(cases.save(any(InsiderCase.class))).thenAnswer(call -> withId(call.getArgument(0), CASE_ID));

        service.onAlertRaised(event(500L, InsiderAlertSeverity.HIGH));

        var opened = ArgumentCaptor.forClass(InsiderCase.class);
        verify(cases).save(opened.capture());
        assertThat(opened.getValue().getStaffId()).isEqualTo(STAFF_ID);
        assertThat(opened.getValue().isOpen()).isTrue();
        assertThat(opened.getValue().getSeverity()).isEqualTo(InsiderAlertSeverity.HIGH);
        var linked = ArgumentCaptor.forClass(InsiderCaseAlert.class);
        verify(links).save(linked.capture());
        assertThat(linked.getValue().getInsiderCaseId()).isEqualTo(CASE_ID);
        assertThat(linked.getValue().getAlertId()).isEqualTo(500L);
    }

    // 열린 사건이 있으면 새 경보도 같은 사건에 묶고, 사건 등급은 높은 쪽으로 오른다
    @Test
    void alertRaised_openCaseExists_linksToSameCase() {
        InsiderCase existing = openCase(InsiderAlertSeverity.HIGH);
        when(links.findById(501L)).thenReturn(Optional.empty());
        when(alertReader.isOpen(501L)).thenReturn(true);
        when(cases.findByStaffIdAndStatus(STAFF_ID, InsiderCaseStatus.OPEN)).thenReturn(Optional.of(existing));

        service.onAlertRaised(event(501L, InsiderAlertSeverity.CRITICAL));

        verify(cases, never()).save(any());
        var linked = ArgumentCaptor.forClass(InsiderCaseAlert.class);
        verify(links).save(linked.capture());
        assertThat(linked.getValue().getInsiderCaseId()).isEqualTo(CASE_ID);
        assertThat(existing.getSeverity()).isEqualTo(InsiderAlertSeverity.CRITICAL);
    }

    // 같은 경보가 등급이 올라 다시 오면 새 줄 없이 묶인 줄과 사건 등급만 올린다
    @Test
    void alertRaised_sameAlertTwice_linksOnceAndRaisesSeverity() {
        InsiderCase existing = openCase(InsiderAlertSeverity.HIGH);
        InsiderCaseAlert link = link(500L, InsiderAlertSeverity.HIGH);
        when(links.findById(500L)).thenReturn(Optional.of(link));
        when(cases.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(existing));

        service.onAlertRaised(event(500L, InsiderAlertSeverity.CRITICAL));

        verify(links, never()).save(any());
        verify(cases, never()).save(any());
        assertThat(link.getSeverity()).isEqualTo(InsiderAlertSeverity.CRITICAL);
        assertThat(existing.getSeverity()).isEqualTo(InsiderAlertSeverity.CRITICAL);
    }

    // 판정으로 닫힌 사건에 묶였던 경보가 늦게 다시 와도 사건을 새로 열지 않는다
    @Test
    void alertRaised_alertLinkedToClosedCase_doesNotReopen() {
        InsiderCase closed = openCase(InsiderAlertSeverity.HIGH);
        closed.decide(InsiderCaseDecision.NORMAL_DUTY, ADMIN.userId(), ADMIN.displayName(), null, T0);
        InsiderCaseAlert link = link(500L, InsiderAlertSeverity.HIGH);
        when(links.findById(500L)).thenReturn(Optional.of(link));
        when(cases.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(closed));

        service.onAlertRaised(event(500L, InsiderAlertSeverity.CRITICAL));

        verify(cases, never()).save(any());
        verify(links, never()).save(any());
        assertThat(closed.getStatus()).isEqualTo(InsiderCaseStatus.CLOSED);
    }

    // 판정이 이미 닫은 경보의 알림이 늦게 와도 그 경보로 사건을 새로 열지 않는다
    @Test
    void alertRaised_alertAlreadyClosed_doesNotOpenCase() {
        when(links.findById(502L)).thenReturn(Optional.empty());
        when(alertReader.isOpen(502L)).thenReturn(false);

        service.onAlertRaised(event(502L, InsiderAlertSeverity.HIGH));

        verify(cases, never()).save(any());
        verify(links, never()).save(any());
    }

    // 배포 전부터 열려 있던 높음 경보는 서버 시작 때 사건으로 묶이고, 다시 시작해도 사건 · 묶음이 늘지 않는다 (#670 Codex 리뷰)
    @Test
    void backfill_openHighAlertWithoutCase_opensCaseOnce() {
        InsiderAlertRaised existing = event(600L, InsiderAlertSeverity.CRITICAL);
        when(alertReader.findOpenRaisedAlerts()).thenReturn(List.of(existing));
        when(alertReader.isOpen(600L)).thenReturn(true);
        List<InsiderCase> saved = new ArrayList<>();
        List<InsiderCaseAlert> linked = new ArrayList<>();
        when(cases.save(any(InsiderCase.class))).thenAnswer(call -> {
            InsiderCase opened = withId(call.getArgument(0), CASE_ID);
            saved.add(opened);
            return opened;
        });
        when(cases.findByStaffIdAndStatus(STAFF_ID, InsiderCaseStatus.OPEN))
                .thenAnswer(call -> saved.stream().filter(InsiderCase::isOpen).findFirst());
        when(links.save(any(InsiderCaseAlert.class))).thenAnswer(call -> {
            linked.add(call.getArgument(0));
            return call.getArgument(0);
        });
        when(links.findById(600L))
                .thenAnswer(call -> linked.stream()
                        .filter(link -> link.getAlertId().equals(600L))
                        .findFirst());

        int first = service.linkOpenAlerts();
        int second = service.linkOpenAlerts();

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getStaffId()).isEqualTo(STAFF_ID);
        assertThat(saved.getFirst().getSeverity()).isEqualTo(InsiderAlertSeverity.CRITICAL);
        assertThat(linked).extracting(InsiderCaseAlert::getAlertId).containsExactly(600L);
    }

    // 새 사건이 열리면 해제 검토 보고서 첫 판을 만들라는 알림을 보낸다 (#675)
    @Test
    void alertRaised_newCase_publishesCaseOpened() {
        when(links.findById(500L)).thenReturn(Optional.empty());
        when(alertReader.isOpen(500L)).thenReturn(true);
        when(cases.findByStaffIdAndStatus(STAFF_ID, InsiderCaseStatus.OPEN)).thenReturn(Optional.empty());
        when(cases.save(any(InsiderCase.class))).thenAnswer(call -> withId(call.getArgument(0), CASE_ID));

        service.onAlertRaised(event(500L, InsiderAlertSeverity.HIGH));

        verify(events).publishEvent(new InsiderCaseChanged(CASE_ID, InsiderCaseChanged.Reason.OPENED, 500L));
    }

    // 열린 사건에 차단(INS-05) 경보가 새로 붙으면 다음 판을 만들라는 알림을 보낸다 (#675)
    @Test
    void alertRaised_exportBlockOnOpenCase_publishesExportBlocked() {
        InsiderCase existing = openCase(InsiderAlertSeverity.HIGH);
        when(links.findById(503L)).thenReturn(Optional.empty());
        when(alertReader.isOpen(503L)).thenReturn(true);
        when(cases.findByStaffIdAndStatus(STAFF_ID, InsiderCaseStatus.OPEN)).thenReturn(Optional.of(existing));

        service.onAlertRaised(event(503L, InsiderAlertSeverity.CRITICAL, InsiderRule.INS_05));

        verify(events).publishEvent(new InsiderCaseChanged(CASE_ID, InsiderCaseChanged.Reason.EXPORT_BLOCKED, 503L));
    }

    // 서버 시작 때 예전 경보로 열린 사건도 해제 검토 보고서 첫 판 알림을 보낸다 (#675) — 알림 경로에만 두면 이 사건엔 보고서가 없다
    @Test
    void linkOpenAlerts_newCase_publishesCaseOpened() {
        when(alertReader.findOpenRaisedAlerts()).thenReturn(List.of(event(600L, InsiderAlertSeverity.HIGH)));
        when(links.findById(600L)).thenReturn(Optional.empty());
        when(alertReader.isOpen(600L)).thenReturn(true);
        when(cases.findByStaffIdAndStatus(STAFF_ID, InsiderCaseStatus.OPEN)).thenReturn(Optional.empty());
        when(cases.save(any(InsiderCase.class))).thenAnswer(call -> withId(call.getArgument(0), CASE_ID));

        service.linkOpenAlerts();

        verify(events).publishEvent(new InsiderCaseChanged(CASE_ID, InsiderCaseChanged.Reason.OPENED, 600L));
    }

    // 정상 업무: 그 직원의 열린 경보를 닫고 정지를 사용 중으로 푼다
    @Test
    void decide_normalDuty_closesOpenAlertsAndUnlocks() {
        InsiderCase insiderCase = givenOpenCaseForDecision();

        service.decide(CASE_ID, new InsiderCaseDecisionRequest("NORMAL_DUTY", null));

        assertThat(insiderCase.getStatus()).isEqualTo(InsiderCaseStatus.CLOSED);
        assertThat(insiderCase.getDecision()).isEqualTo(InsiderCaseDecision.NORMAL_DUTY);
        verify(alertCloser).closeOpenAlerts(eq(STAFF_ID), eq(ADMIN.userId()), eq("관리자"), anyString());
        StaffLockRelease release = capturedRelease();
        assertThat(release.outcome()).isEqualTo(StaffLockOutcome.RESTORE);
        assertThat(release.caseId()).isEqualTo(CASE_ID);
        assertThat(release.decision()).isEqualTo("NORMAL_DUTY");
    }

    // 주의 조치: 정지를 풀되 사건과 P1 해제 기록에 경고 판정이 남는다
    @Test
    void decide_cautionNotice_unlocksAndKeepsWarningDecision() {
        InsiderCase insiderCase = givenOpenCaseForDecision();

        service.decide(CASE_ID, new InsiderCaseDecisionRequest("CAUTION_NOTICE", "업무 범위 재교육"));

        assertThat(insiderCase.getDecision()).isEqualTo(InsiderCaseDecision.CAUTION_NOTICE);
        assertThat(insiderCase.getDecisionMemo()).isEqualTo("업무 범위 재교육");
        verify(alertCloser).closeOpenAlerts(eq(STAFF_ID), eq(ADMIN.userId()), eq("관리자"), anyString());
        StaffLockRelease release = capturedRelease();
        assertThat(release.outcome()).isEqualTo(StaffLockOutcome.RESTORE);
        assertThat(release.decision()).isEqualTo("CAUTION_NOTICE");
    }

    // 징계 요청: 이미 정지된 직원은 정지를 유지한 채 사건을 닫는다
    @Test
    void decide_discipline_keepsLockAndClosesCase() {
        InsiderCase insiderCase = givenOpenCaseForDecision();
        when(securityCases.findOpenByAssignee(STAFF_ID)).thenReturn(List.of());
        when(locker.isLocked(STAFF_ID)).thenReturn(true);

        service.decide(CASE_ID, new InsiderCaseDecisionRequest("DISCIPLINE_REQUEST", null));

        assertThat(insiderCase.getStatus()).isEqualTo(InsiderCaseStatus.CLOSED);
        assertThat(insiderCase.getDecision()).isEqualTo(InsiderCaseDecision.DISCIPLINE_REQUEST);
        verify(alertCloser).closeOpenAlerts(eq(STAFF_ID), eq(ADMIN.userId()), eq("관리자"), anyString());
        verify(locker, never()).release(any());
        verify(locker, never()).lockByAdmin(anyLong(), anyLong(), any(), any());
    }

    // 징계 요청: 아직 정지되지 않은 직원은 지금 정지하고, 근거로 가장 최근 경보를 남긴다
    @Test
    void decide_discipline_notLocked_locksAccount() {
        InsiderCase insiderCase = givenOpenCaseForDecision();
        when(securityCases.findOpenByAssignee(STAFF_ID)).thenReturn(List.of());
        when(locker.isLocked(STAFF_ID)).thenReturn(false);
        when(links.findFirstByInsiderCaseIdOrderByRaisedAtDescAlertIdDesc(CASE_ID))
                .thenReturn(Optional.of(link(777L, InsiderAlertSeverity.HIGH)));

        service.decide(CASE_ID, new InsiderCaseDecisionRequest("DISCIPLINE_REQUEST", null));

        verify(locker).lockByAdmin(STAFF_ID, ADMIN.userId(), StaffLockReason.INSIDER_CASE_DECISION, 777L);
        verify(locker, never()).release(any());
        assertThat(insiderCase.getDecision()).isEqualTo(InsiderCaseDecision.DISCIPLINE_REQUEST);
    }

    // 징계 요청: 그 직원이 맡은 FDS 사건이 남아 있으면 거부하고 사건 · 경보 · 정지를 그대로 둔다
    @Test
    void decide_discipline_withRemainingFdsCases_isRejected() {
        InsiderCase insiderCase = givenOpenCaseForDecision();
        when(securityCases.findOpenByAssignee(STAFF_ID))
                .thenReturn(List.of(SecurityCase.open(3L, "cust-1", CaseType.PAYMENT_FRAUD, T0)));

        assertThatThrownBy(() -> service.decide(CASE_ID, new InsiderCaseDecisionRequest("DISCIPLINE_REQUEST", null)))
                .isInstanceOf(InvestigationException.class)
                .extracting("errorCode")
                .isEqualTo(InvestigationErrorCode.INSIDER_CASE_HAS_OPEN_FDS_CASES);
        assertThat(insiderCase.isOpen()).isTrue();
        verify(alertCloser, never()).closeOpenAlerts(any(), any(), any(), any());
        verify(locker, never()).lockByAdmin(anyLong(), anyLong(), any(), any());
    }

    // 이미 판정한 사건은 다른 판정으로 덮어쓰지 않는다
    @Test
    void decide_closedCase_isRejected() {
        when(guard.requireAdmin()).thenReturn(ADMIN);
        InsiderCase closed = openCase(InsiderAlertSeverity.HIGH);
        closed.decide(InsiderCaseDecision.DISCIPLINE_REQUEST, ADMIN.userId(), ADMIN.displayName(), null, T0);
        when(cases.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> service.decide(CASE_ID, new InsiderCaseDecisionRequest("NORMAL_DUTY", null)))
                .isInstanceOf(InvestigationException.class)
                .extracting("errorCode")
                .isEqualTo(InvestigationErrorCode.INSIDER_CASE_CLOSED);
        assertThat(closed.getDecision()).isEqualTo(InsiderCaseDecision.DISCIPLINE_REQUEST);
        verify(locker, never()).release(any());
    }

    // 징계 사건의 정지 해제: 사유 · 결과를 사건에 남기고, 관리자가 고른 결과로 P1 정지를 푼다
    @Test
    void releaseLock_disciplinedClosedCase_unlocksAndRecordsReason() {
        InsiderCase insiderCase = givenDisciplinedCaseForRelease();
        when(cases.existsByStaffIdAndStatus(STAFF_ID, InsiderCaseStatus.OPEN)).thenReturn(false);
        when(locker.isLocked(STAFF_ID)).thenReturn(true);

        service.releaseLock(CASE_ID, new InsiderLockReleaseRequest("DEACTIVATE", " 퇴사 처리 "));

        assertThat(insiderCase.getLockReleaseReason()).isEqualTo("퇴사 처리");
        assertThat(insiderCase.getLockReleaseOutcome()).isEqualTo(StaffLockOutcome.DEACTIVATE);
        assertThat(insiderCase.getLockReleasedByAdminId()).isEqualTo(ADMIN.userId());
        StaffLockRelease release = capturedRelease();
        assertThat(release.outcome()).isEqualTo(StaffLockOutcome.DEACTIVATE);
        assertThat(release.caseId()).isEqualTo(CASE_ID);
        assertThat(release.staffId()).isEqualTo(STAFF_ID);
    }

    // 징계가 아닌 판정으로 닫힌 사건으로는 정지를 풀 수 없다
    @Test
    void releaseLock_caseNotDisciplined_isRejected() {
        when(guard.requireAdmin()).thenReturn(ADMIN);
        InsiderCase normal = openCase(InsiderAlertSeverity.HIGH);
        normal.decide(InsiderCaseDecision.NORMAL_DUTY, ADMIN.userId(), ADMIN.displayName(), null, T0);
        when(cases.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(normal));

        assertThatThrownBy(() -> service.releaseLock(CASE_ID, new InsiderLockReleaseRequest("RESTORE", "소명 확인")))
                .isInstanceOf(InvestigationException.class)
                .extracting("errorCode")
                .isEqualTo(InvestigationErrorCode.INSIDER_LOCK_RELEASE_NOT_ALLOWED);
        verify(locker, never()).release(any());
    }

    // 그 직원에게 새로 열린 내부자 사건이 있으면 옛 징계 사건으로 정지를 풀지 않는다
    @Test
    void releaseLock_staffHasOpenInsiderCase_isRejected() {
        InsiderCase insiderCase = givenDisciplinedCaseForRelease();
        when(cases.existsByStaffIdAndStatus(STAFF_ID, InsiderCaseStatus.OPEN)).thenReturn(true);

        assertThatThrownBy(() -> service.releaseLock(CASE_ID, new InsiderLockReleaseRequest("RESTORE", "소명 확인")))
                .isInstanceOf(InvestigationException.class)
                .extracting("errorCode")
                .isEqualTo(InvestigationErrorCode.STAFF_HAS_OPEN_INSIDER_CASE);
        assertThat(insiderCase.isLockReleased()).isFalse();
        verify(locker, never()).release(any());
    }

    // 이미 정지를 푼 사건은 다시 풀지 않는다 — 첫 해제의 누가 · 왜가 남는다
    @Test
    void releaseLock_alreadyReleased_isRejected() {
        InsiderCase insiderCase = givenDisciplinedCaseForRelease();
        insiderCase.recordLockRelease(99L, "첫 관리자", "첫 사유", StaffLockOutcome.RESTORE, T0);

        assertThatThrownBy(() -> service.releaseLock(CASE_ID, new InsiderLockReleaseRequest("DEACTIVATE", "두 번째")))
                .isInstanceOf(InvestigationException.class)
                .extracting("errorCode")
                .isEqualTo(InvestigationErrorCode.INSIDER_LOCK_ALREADY_RELEASED);
        assertThat(insiderCase.getLockReleasedByAdminId()).isEqualTo(99L);
        assertThat(insiderCase.getLockReleaseReason()).isEqualTo("첫 사유");
        verify(locker, never()).release(any());
    }

    private InsiderCase givenOpenCaseForDecision() {
        when(guard.requireAdmin()).thenReturn(ADMIN);
        InsiderCase insiderCase = openCase(InsiderAlertSeverity.HIGH);
        when(cases.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(insiderCase));
        return insiderCase;
    }

    private InsiderCase givenDisciplinedCaseForRelease() {
        when(guard.requireAdmin()).thenReturn(ADMIN);
        InsiderCase insiderCase = openCase(InsiderAlertSeverity.CRITICAL);
        insiderCase.decide(InsiderCaseDecision.DISCIPLINE_REQUEST, ADMIN.userId(), ADMIN.displayName(), null, T0);
        when(cases.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(insiderCase));
        return insiderCase;
    }

    private StaffLockRelease capturedRelease() {
        var release = ArgumentCaptor.forClass(StaffLockRelease.class);
        verify(locker).release(release.capture());
        return release.getValue();
    }

    private static InsiderCase openCase(InsiderAlertSeverity severity) {
        return withId(InsiderCase.open(STAFF_ID, severity, T0), CASE_ID);
    }

    private static InsiderCaseAlert link(Long alertId, InsiderAlertSeverity severity) {
        return InsiderCaseAlert.link(CASE_ID, alertId, InsiderRule.INS_01, severity, "담당 밖 접근 5건", T0, T0);
    }

    private static InsiderCase withId(InsiderCase insiderCase, Long id) {
        ReflectionTestUtils.setField(insiderCase, "id", id);
        return insiderCase;
    }

    private static InsiderAlertRaised event(Long alertId, InsiderAlertSeverity severity) {
        return event(alertId, severity, InsiderRule.INS_01);
    }

    private static InsiderAlertRaised event(Long alertId, InsiderAlertSeverity severity, InsiderRule rule) {
        return InsiderAlertRaised.builder()
                .alertId(alertId)
                .staffId(STAFF_ID)
                .rule(rule)
                .severity(severity)
                .summary("담당 밖 접근 5건")
                .raisedAt(T0.plusSeconds(60))
                .build();
    }
}
