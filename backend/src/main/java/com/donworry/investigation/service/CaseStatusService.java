package com.donworry.investigation.service;

import com.donworry.investigation.entity.ActionHandoff;
import com.donworry.investigation.entity.CaseHistory;
import com.donworry.investigation.entity.CaseHistoryEventType;
import com.donworry.investigation.entity.CaseStatus;
import com.donworry.investigation.entity.HandoffStatus;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.ActionHandoffRepository;
import com.donworry.investigation.repository.CaseHistoryRepository;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.PaymentDecisionRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.InvestigationRules.CaseSnapshot;
import com.donworry.investigation.service.InvestigationRules.ReportProgress;
import com.donworry.investigation.service.ReportGateway.ReportSnapshot;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Case 상태를 현재 결제 · 판정 · 인계 · 보고서 상태에 맞춰 다시 계산한다. 판단은 InvestigationRules가 한다
@Service
@RequiredArgsConstructor
public class CaseStatusService {

    private final SecurityCaseRepository securityCaseRepository;
    private final CasePaymentRepository casePaymentRepository;
    private final PaymentDecisionRepository paymentDecisionRepository;
    private final ActionHandoffRepository actionHandoffRepository;
    private final CaseHistoryRepository caseHistoryRepository;
    private final ReportGateway reportGateway;

    // 호출자 트랜잭션 안에서, findByIdForUpdate · findMergeCandidates로 잠근 Case에만 부른다
    @Transactional(propagation = Propagation.MANDATORY)
    public void refresh(SecurityCase securityCase, Instant now) {
        Long caseId = securityCase.getId();
        CaseStatus current = securityCase.getStatus();
        // 고객이 승인을 취소한 결제는 금융 처리가 끝난 것으로 보고 판정 · 인계 계산에서 뺀다 (#382)
        // 그 결제의 기존 판정 · 인계(예: P5가 PAYMENT_CANCELED_BY_CUSTOMER로 거절한 FAILED)는 보존하되 상태를 막지 않는다
        Set<Long> canceled = canceledCasePayments(caseId).keySet();
        List<HandoffStatus> handoffs = actionHandoffRepository.findByDecisionCasePaymentSecurityCaseId(caseId).stream()
                .filter(handoff -> !canceled.contains(
                        handoff.getDecision().getCasePayment().getId()))
                .map(ActionHandoff::getStatus)
                .toList();
        int paymentCount = casePaymentRepository
                .findBySecurityCaseIdOrderByLinkedAtAsc(caseId)
                .size();
        int decidedCount = (int) paymentDecisionRepository.findByCasePaymentSecurityCaseId(caseId).stream()
                .filter(decision -> !canceled.contains(decision.getCasePayment().getId()))
                .count();
        CaseSnapshot snapshot = new CaseSnapshot(
                current,
                paymentCount - canceled.size(),
                canceled.size(),
                decidedCount,
                handoffs,
                reportProgress(caseId, securityCase.getEvidenceVersion()));
        CaseStatus next = InvestigationRules.nextStatus(snapshot);
        if (next == current) {
            return;
        }
        if (next == CaseStatus.CLOSED) {
            securityCase.close(now);
            // 모든 결제를 고객이 취소해 닫히면 사유를 남긴다 (#382). 직원 판정으로 끝난 것과 구분한다
            Map<String, Object> data = snapshot.paymentCount() == 0
                    ? Map.of("caseId", caseId, "reason", "CUSTOMER_CANCELED")
                    : Map.of("caseId", caseId);
            caseHistoryRepository.save(CaseHistory.bySystem(securityCase, CaseHistoryEventType.CASE_CLOSED, data, now));
            return;
        }
        securityCase.changeStatus(next);
        caseHistoryRepository.save(CaseHistory.bySystem(
                securityCase,
                CaseHistoryEventType.CASE_STATUS_CHANGED,
                Map.of("from", current.name(), "to", next.name()),
                now));
    }

    // 고객 취소를 반영한 연결 결제 ID → cancellationId (#382). 원본은 P5, 이 값은 P3가 반영한 이력이다
    // 상태 계산 · 판정 차단 · 조회가 같이 쓴다
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Map<Long, Long> canceledCasePayments(Long caseId) {
        Map<Long, Long> canceled = new HashMap<>();
        for (CaseHistory history : caseHistoryRepository.findBySecurityCaseIdAndEventType(
                caseId, CaseHistoryEventType.PAYMENT_CANCELED_BY_CUSTOMER)) {
            Map<String, Object> data = history.getEventData();
            canceled.put(number(data.get("casePaymentId")), number(data.get("cancellationId")));
        }
        return canceled;
    }

    private static Long number(Object value) {
        return ((Number) Objects.requireNonNull(value, "eventData")).longValue();
    }

    // 커밋 뒤 리스너에서 부른다 — 끝난 트랜잭션에 합류하지 않도록 새 트랜잭션을 연다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refreshById(Long caseId) {
        securityCaseRepository
                .findByIdForUpdate(caseId)
                .filter(securityCase -> !securityCase.isClosed())
                .ifPresent(securityCase -> refresh(securityCase, Instant.now()));
    }

    // 최신 보고서가 현재 증거 버전보다 오래됐으면 재조사 중으로 본다 (fds 정본 03 §3.5)
    // 최종 실패(FAILED)도 직원이 수동 판정할 수 있으므로 완료로 본다
    private ReportProgress reportProgress(Long caseId, int evidenceVersion) {
        Optional<ReportSnapshot> latest = reportGateway.latest(caseId);
        if (latest.isEmpty()) {
            return ReportProgress.NONE;
        }
        ReportSnapshot report = latest.get();
        if (report.evidenceVersion() < evidenceVersion) {
            return ReportProgress.IN_PROGRESS;
        }
        return report.isSucceeded() || report.isFailed() ? ReportProgress.COMPLETED : ReportProgress.IN_PROGRESS;
    }
}
