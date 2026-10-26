package com.donworry.investigation.service;

import com.donworry.investigation.dto.DecisionSubmitRequest;
import com.donworry.investigation.entity.ActionHandoff;
import com.donworry.investigation.entity.CaseHistory;
import com.donworry.investigation.entity.CaseHistoryEventType;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.PaymentDecision;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.ActionHandoffRepository;
import com.donworry.investigation.repository.CaseHistoryRepository;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.PaymentDecisionRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.InvestigationEvents.HandoffRequested;
import com.donworry.investigation.service.ReportGateway.ReportSnapshot;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 직원 결제별 판정과 금융 조치 인계 요청을 같은 트랜잭션에 저장한다 (fds 정본 02 §2.8 · §2.9)
@Service
@RequiredArgsConstructor
public class CaseDecisionService {

    private final SecurityCaseRepository securityCaseRepository;
    private final CasePaymentRepository casePaymentRepository;
    private final PaymentDecisionRepository paymentDecisionRepository;
    private final ActionHandoffRepository actionHandoffRepository;
    private final CaseHistoryRepository caseHistoryRepository;
    private final CaseStatusService caseStatusService;
    private final ReportGateway reportGateway;
    private final ApplicationEventPublisher events;
    private final SecurityStaffGuard securityStaffGuard;

    // P5 호출은 커밋 뒤 HandoffRequested 리스너가 한다 — 판정 저장이 롤백되면 인계 요청도 나가지 않는다
    @Transactional
    public void decide(Long caseId, DecisionSubmitRequest request) {
        // 판정자는 요청 값이 아니라 로그인 세션으로 정한다 — 다른 사람 ID로 판정을 남기지 못하게 한다
        Long staffId = securityStaffGuard.requireSecurityStaffId();
        // 병합과 같은 Case 행 잠금 — 판정 중에 결제가 끼어들어 증거 버전이 바뀌지 않게 한다
        SecurityCase securityCase = securityCaseRepository
                .findByIdForUpdate(caseId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.CASE_NOT_FOUND));
        if (securityCase.isClosed()) {
            throw new InvestigationException(InvestigationErrorCode.CASE_CLOSED);
        }
        if (request.evidenceVersion() != securityCase.getEvidenceVersion()) {
            throw new InvestigationException(InvestigationErrorCode.EVIDENCE_VERSION_CONFLICT);
        }
        validateReport(caseId, securityCase.getEvidenceVersion(), request.reportId());

        Map<Long, CasePayment> payments = casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(caseId).stream()
                .collect(Collectors.toMap(CasePayment::getId, Function.identity()));
        Set<Long> decided = paymentDecisionRepository.findByCasePaymentSecurityCaseId(caseId).stream()
                .map(decision -> decision.getCasePayment().getId())
                .collect(Collectors.toSet());
        Set<Long> inRequest = new HashSet<>();

        Instant now = Instant.now();
        for (DecisionSubmitRequest.Item item : request.decisions()) {
            CasePayment casePayment = payments.get(item.casePaymentId());
            if (casePayment == null) {
                throw new InvestigationException(InvestigationErrorCode.PAYMENT_NOT_IN_CASE);
            }
            if (!inRequest.add(item.casePaymentId())) {
                throw new InvestigationException(InvestigationErrorCode.DUPLICATE_PAYMENT_IN_REQUEST);
            }
            if (decided.contains(item.casePaymentId())) {
                throw new InvestigationException(InvestigationErrorCode.PAYMENT_ALREADY_DECIDED);
            }
            PaymentDecision decision = paymentDecisionRepository.save(PaymentDecision.confirm(
                    casePayment,
                    staffId,
                    request.reportId(),
                    item.decision(),
                    item.reason(),
                    securityCase.getEvidenceVersion(),
                    now));
            ActionHandoff handoff = actionHandoffRepository.save(ActionHandoff.request(decision, now));
            caseHistoryRepository.save(CaseHistory.byStaff(
                    securityCase,
                    CaseHistoryEventType.DECISION_CONFIRMED,
                    staffId,
                    Map.of(
                            "decisionId", decision.getId(),
                            "casePaymentId", casePayment.getId(),
                            "decision", item.decision().name()),
                    now));
            caseHistoryRepository.save(CaseHistory.byStaff(
                    securityCase,
                    CaseHistoryEventType.HANDOFF_REQUESTED,
                    staffId,
                    Map.of(
                            "handoffId", handoff.getId(),
                            "decisionId", decision.getId(),
                            "actionType", handoff.getActionType().name()),
                    now));
            events.publishEvent(new HandoffRequested(handoff.getId()));
        }
        caseStatusService.refresh(securityCase, now);
    }

    // 보고서를 참고했으면 최신 · 성공 · 같은 증거 버전이어야 한다. 보고서 없이 판정은 최종 실패 뒤에만 허용한다
    private void validateReport(Long caseId, int evidenceVersion, Long reportId) {
        Optional<ReportSnapshot> latest = reportGateway.latest(caseId);
        boolean sameVersion = latest.map(report -> report.evidenceVersion() == evidenceVersion)
                .orElse(false);
        if (reportId == null) {
            if (!(sameVersion && latest.get().isFailed())) {
                throw new InvestigationException(InvestigationErrorCode.MANUAL_DECISION_NOT_ALLOWED);
            }
            return;
        }
        if (!(sameVersion
                && latest.get().isSucceeded()
                && reportId.equals(latest.get().reportId()))) {
            throw new InvestigationException(InvestigationErrorCode.REPORT_NOT_LATEST);
        }
    }
}
