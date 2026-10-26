package com.donworry.investigation.service;

import com.donworry.investigation.dto.MerchantCaseDetailResponse;
import com.donworry.investigation.dto.MerchantCaseDetailResponse.PaymentResult;
import com.donworry.investigation.dto.MerchantCaseListResponse;
import com.donworry.investigation.dto.MerchantCaseListResponse.CaseRow;
import com.donworry.investigation.dto.MerchantCaseListResponse.Overview;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseStatus;
import com.donworry.investigation.entity.Decision;
import com.donworry.investigation.entity.PaymentDecision;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.PaymentDecisionRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.PaymentInfoReader.PaymentInfo;
import com.donworry.investigation.service.ReportGateway.ReportSnapshot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 고객사 "보안 조치 결과" 조회 — 호출자가 MerchantViewerGuard로 확인한 가맹점의 Case만 읽는다 (fds 정본 02 §2.12 ②, #205)
// P5 PaymentReader는 가맹점 범위를 검사하지 않으므로, 가맹점이 확인된 Case에 연결된 결제 ID로만 조회한다 (ADR-0026)
@Service
@RequiredArgsConstructor
public class MerchantCaseQueryService {

    static final String INVESTIGATING = "INVESTIGATING";

    private final SecurityCaseRepository securityCaseRepository;
    private final CasePaymentRepository casePaymentRepository;
    private final PaymentDecisionRepository paymentDecisionRepository;
    private final PaymentInfoReader paymentInfoReader;
    private final ReportGateway reportGateway;

    @Transactional(readOnly = true)
    public MerchantCaseListResponse list(Long merchantId) {
        List<CaseRow> rows = securityCaseRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId).stream()
                .map(securityCase -> CaseRow.builder()
                        .caseId(securityCase.getId())
                        .displayStatus(displayStatus(securityCase.getStatus()))
                        .createdAt(securityCase.getCreatedAt())
                        .closedAt(securityCase.getClosedAt())
                        .paymentCount(casePaymentRepository
                                .findBySecurityCaseIdOrderByLinkedAtAsc(securityCase.getId())
                                .size())
                        .build())
                .toList();
        int investigating = (int) rows.stream()
                .filter(row -> INVESTIGATING.equals(row.displayStatus()))
                .count();
        return new MerchantCaseListResponse(
                new Overview(
                        investigating,
                        paymentDecisionRepository.countByCasePaymentSecurityCaseMerchantIdAndDecision(
                                merchantId, Decision.ACCEPT),
                        paymentDecisionRepository.countByCasePaymentSecurityCaseMerchantIdAndDecision(
                                merchantId, Decision.REJECT)),
                rows);
    }

    // 다른 가맹점 Case는 없는 Case와 같은 404로 답한다 — 다른 가맹점 Case가 있다는 사실도 알리지 않는다
    @Transactional(readOnly = true)
    public MerchantCaseDetailResponse detail(Long merchantId, Long caseId) {
        SecurityCase securityCase = securityCaseRepository
                .findById(caseId)
                .filter(found -> merchantId.equals(found.getMerchantId()))
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.CASE_NOT_FOUND));
        List<CasePayment> casePayments = casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(caseId);
        Map<Long, PaymentInfo> infos = paymentInfoReader.findAll(
                casePayments.stream().map(CasePayment::getPaymentId).toList());
        Map<Long, PaymentDecision> decisions =
                paymentDecisionRepository.findByCasePaymentSecurityCaseId(caseId).stream()
                        .collect(Collectors.toMap(
                                decision -> decision.getCasePayment().getId(), Function.identity()));
        // 최신 보고서가 현재 증거 버전을 조사해 성공했을 때만 요약 · 결제별 결론을 보여 준다
        // 병합 뒤 새 보고서가 아직 없거나 실패했으면 이전 버전 요약을 내보내지 않는다 (CaseStatusService.reportProgress와 같은 기준)
        Map<String, Object> reportBody = reportGateway
                .latest(caseId)
                .filter(report -> report.evidenceVersion() == securityCase.getEvidenceVersion())
                .filter(ReportSnapshot::isSucceeded)
                .map(ReportSnapshot::reportBody)
                .orElse(null);
        Map<String, String> recommendations = recommendations(reportBody);
        return MerchantCaseDetailResponse.builder()
                .caseId(securityCase.getId())
                .displayStatus(displayStatus(securityCase.getStatus()))
                .createdAt(securityCase.getCreatedAt())
                .closedAt(securityCase.getClosedAt())
                .summary(summary(reportBody))
                .directInvestigation(decisions.values().stream().anyMatch(decision -> decision.getReportId() == null))
                .payments(casePayments.stream()
                        .map(casePayment -> {
                            Optional<PaymentInfo> info = Optional.ofNullable(infos.get(casePayment.getPaymentId()));
                            Optional<PaymentDecision> decision =
                                    Optional.ofNullable(decisions.get(casePayment.getId()));
                            return PaymentResult.builder()
                                    .paymentId(casePayment.getPaymentId())
                                    .amount(info.map(PaymentInfo::amount).orElse(null))
                                    .requestedAt(
                                            info.map(PaymentInfo::requestedAt).orElse(null))
                                    .paymentStatus(
                                            info.map(PaymentInfo::paymentStatus).orElse(null))
                                    .decision(decision.map(
                                                    found -> found.getDecision().name())
                                            .orElse(null))
                                    .aiRecommendation(recommendations.get(String.valueOf(casePayment.getPaymentId())))
                                    .build();
                        })
                        .toList())
                .build();
    }

    // 고객사 화면의 3단계 표시 (fds 정본 02 §2.12 ②) — 확정 거절(ACTION_FAILED)도 "처리 진행 중"으로 묶는다
    static String displayStatus(CaseStatus status) {
        return switch (status) {
            case OPEN, INVESTIGATING, DECISION_PENDING -> INVESTIGATING;
            case ACTION_PENDING, ACTION_FAILED -> "PROCESSING";
            case CLOSED -> "CLOSED";
        };
    }

    // report_body 키는 화면 ReportBody(ADR-0009 결정 4)를 따른다. 핵심 요약 외의 키는 읽지 않는다
    private static List<String> summary(Map<String, Object> reportBody) {
        if (reportBody == null || !(reportBody.get("summary") instanceof List<?> lines)) {
            return null;
        }
        return lines.stream().map(String::valueOf).toList();
    }

    // 결제별 AI 결론만 꺼낸다. 보고서의 결제 ID는 숫자 · 문자열 어느 쪽이든 문자열로 맞춰 비교한다
    private static Map<String, String> recommendations(Map<String, Object> reportBody) {
        Map<String, String> found = new HashMap<>();
        if (reportBody != null && reportBody.get("paymentAnalyses") instanceof List<?> analyses) {
            for (Object analysis : analyses) {
                if (analysis instanceof Map<?, ?> fields
                        && fields.get("paymentId") != null
                        && fields.get("recommendation") != null) {
                    found.put(String.valueOf(fields.get("paymentId")), String.valueOf(fields.get("recommendation")));
                }
            }
        }
        return found;
    }
}
