package com.donworry.investigation.dto;

import com.donworry.investigation.entity.ActionHandoff;
import com.donworry.investigation.entity.CaseHistory;
import com.donworry.investigation.entity.PaymentDecision;
import com.donworry.investigation.entity.SecurityCase;
import java.time.Instant;
import java.util.List;
import java.util.Map;

// Case 상세 — 화면 CaseDetail과 같은 모양. 결제 금액 · FDS 점수 · 보고서는 소유 담당에서 조회한 값이다
public record CaseDetailResponse(
        SecurityCaseResponse securityCase,
        List<CasePaymentResponse> payments,
        List<AiReportResponse> reports,
        List<DecisionResponse> decisions,
        List<HandoffResponse> handoffs,
        List<HistoryResponse> history) {

    public record SecurityCaseResponse(
            Long caseId,
            Long merchantId,
            String customerRef,
            String caseType,
            String status,
            int evidenceVersion,
            Instant createdAt,
            Instant mergeUntil,
            Instant closedAt) {

        public static SecurityCaseResponse from(SecurityCase securityCase, Instant mergeUntil) {
            return new SecurityCaseResponse(
                    securityCase.getId(),
                    securityCase.getMerchantId(),
                    securityCase.getCustomerRef(),
                    securityCase.getCaseType().name(),
                    securityCase.getStatus().name(),
                    securityCase.getEvidenceVersion(),
                    securityCase.getCreatedAt(),
                    mergeUntil,
                    securityCase.getClosedAt());
        }
    }

    // P5 결제 · P2 평가 값은 조회하지 못하면 null로 둔다 — 추정값을 채우지 않는다
    public record CasePaymentResponse(
            Long casePaymentId,
            Long paymentId,
            Instant linkedAt,
            Long amount,
            String paymentStatus,
            Instant requestedAt,
            Integer riskScore,
            String fdsResult,
            List<RuleHitResponse> ruleHits) {}

    // P2 MATCH 적중 룰 — 경보 상세 화면과 같은 정보(코드 · 평가 당시 이름 · 더한 점수). 시연 결제의 이름 · 점수는 시연용 값이다
    public record RuleHitResponse(String code, String name, Integer appliedScore) {}

    public record AiReportResponse(
            Long reportId,
            Long caseId,
            int reportVersion,
            int evidenceVersion,
            String status,
            Map<String, Object> reportBody,
            Instant createdAt,
            Instant completedAt) {}

    public record DecisionResponse(
            Long decisionId,
            Long casePaymentId,
            Long staffId,
            Long reportId,
            String decision,
            String reason,
            int evidenceVersion,
            Instant decidedAt) {

        public static DecisionResponse from(PaymentDecision decision) {
            return new DecisionResponse(
                    decision.getId(),
                    decision.getCasePayment().getId(),
                    decision.getStaffId(),
                    decision.getReportId(),
                    decision.getDecision().name(),
                    decision.getReason(),
                    decision.getEvidenceVersion(),
                    decision.getDecidedAt());
        }
    }

    public record HandoffResponse(
            Long handoffId,
            Long decisionId,
            String actionType,
            String status,
            Long targetOperationId,
            Instant requestedAt,
            Instant lastAttemptedAt,
            Instant acceptedAt,
            String lastErrorCode) {

        public static HandoffResponse from(ActionHandoff handoff) {
            return new HandoffResponse(
                    handoff.getId(),
                    handoff.getDecision().getId(),
                    handoff.getActionType().name(),
                    handoff.getStatus().name(),
                    handoff.getTargetOperationId(),
                    handoff.getRequestedAt(),
                    handoff.getLastAttemptedAt(),
                    handoff.getAcceptedAt(),
                    handoff.getLastErrorCode());
        }
    }

    public record HistoryResponse(
            Long historyId,
            Long caseId,
            String eventType,
            String actorType,
            Long actorId,
            Map<String, Object> eventData,
            Instant createdAt) {

        public static HistoryResponse from(CaseHistory history) {
            return new HistoryResponse(
                    history.getId(),
                    history.getSecurityCase().getId(),
                    history.getEventType().name(),
                    history.getActorType().name(),
                    history.getActorId(),
                    history.getEventData(),
                    history.getCreatedAt());
        }
    }
}
