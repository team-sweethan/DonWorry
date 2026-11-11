package com.donworry.investigation.service;

import com.donworry.identity.api.StaffAccessType;
import com.donworry.investigation.dto.DecisionSubmitRequest;
import com.donworry.investigation.entity.ActionHandoff;
import com.donworry.investigation.entity.AssignmentRequestStatus;
import com.donworry.investigation.entity.CaseHistory;
import com.donworry.investigation.entity.CaseHistoryEventType;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.PaymentDecision;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.ActionHandoffRepository;
import com.donworry.investigation.repository.CaseAssignmentRequestRepository;
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
    private final CustomerVoidReader customerVoidReader;
    private final CaseAssignmentRequestRepository assignmentRequestRepository;
    private final CaseAccessGuard caseAccessGuard;

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
        // 열린 사건은 담당자만 판정한다 (#612). 대기 요청 검사보다 먼저 — 담당이 아닌 직원에게 요청 상태를 알리지 않게
        caseAccessGuard.requireAccess(securityCase, staffId, StaffAccessType.CASE_DECISION);
        // 담당 변경 요청이 대기 중이면 판정만 막는다 (#608) — 바뀌기 전 담당이 판정하지 않게
        // 요청 생성도 같은 사건 행 잠금을 먼저 잡으므로, 잠금 뒤 이 조회는 끼어든 요청까지 본다
        if (assignmentRequestRepository.existsByCaseIdAndStatus(caseId, AssignmentRequestStatus.PENDING)) {
            throw new InvestigationException(InvestigationErrorCode.ASSIGNMENT_CHANGE_PENDING);
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
        // 고객이 승인을 취소한 결제는 새 판정 대상이 아니다 (#382). P3에 반영된 이력과 함께 P5 원본도 본다 —
        // 취소 이벤트가 아직 반영되기 전이어도 판정 · 인계를 만들지 않는다
        Set<Long> canceled = caseStatusService.canceledCasePayments(caseId).keySet();

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
            if (canceled.contains(item.casePaymentId())
                    || customerVoidReader
                            .find(securityCase.getMerchantId(), casePayment.getPaymentId())
                            .isPresent()) {
                throw new InvestigationException(InvestigationErrorCode.PAYMENT_CANCELED_BY_CUSTOMER);
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
