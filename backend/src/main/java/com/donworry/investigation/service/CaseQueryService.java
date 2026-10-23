package com.donworry.investigation.service;

import com.donworry.investigation.dto.CaseDetailResponse;
import com.donworry.investigation.dto.CaseDetailResponse.AiReportResponse;
import com.donworry.investigation.dto.CaseDetailResponse.CasePaymentResponse;
import com.donworry.investigation.dto.CaseDetailResponse.DecisionResponse;
import com.donworry.investigation.dto.CaseDetailResponse.HandoffResponse;
import com.donworry.investigation.dto.CaseDetailResponse.HistoryResponse;
import com.donworry.investigation.dto.CaseDetailResponse.SecurityCaseResponse;
import com.donworry.investigation.dto.CaseSummaryResponse;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.ActionHandoffRepository;
import com.donworry.investigation.repository.CaseHistoryRepository;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.PaymentDecisionRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.PaymentInfoReader.PaymentInfo;
import com.donworry.investigation.service.ReportGateway.ReportSnapshot;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 보안 직원 화면용 Case 조회 — P3 데이터에 P5 결제 · P2 평가 · P4 보고서를 합친다
@Service
@RequiredArgsConstructor
public class CaseQueryService {

    private final SecurityCaseRepository securityCaseRepository;
    private final CasePaymentRepository casePaymentRepository;
    private final PaymentDecisionRepository paymentDecisionRepository;
    private final ActionHandoffRepository actionHandoffRepository;
    private final CaseHistoryRepository caseHistoryRepository;
    private final PaymentInfoReader paymentInfoReader;
    private final ReportGateway reportGateway;

    // 보안 직원(merchant_id 없음)은 모든 가맹점 Case를 본다. 화면에서 가맹점을 고르면 그 가맹점만 거른다
    // 이건 표시 필터일 뿐 인가가 아니다 — 역할 · 가맹점 권한 검사는 identity 공개 계약이 생긴 뒤 붙인다
    @Transactional(readOnly = true)
    public List<CaseSummaryResponse> list(Long merchantId) {
        List<SecurityCase> cases = merchantId == null
                ? securityCaseRepository.findAllByOrderByCreatedAtDesc()
                : securityCaseRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
        return cases.stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public CaseDetailResponse detail(Long caseId) {
        SecurityCase securityCase = securityCaseRepository
                .findById(caseId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.CASE_NOT_FOUND));
        return new CaseDetailResponse(
                SecurityCaseResponse.from(securityCase, InvestigationRules.mergeDeadline(securityCase.getCreatedAt())),
                casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(caseId).stream()
                        .map(this::payment)
                        .toList(),
                reportGateway.findByCase(caseId).stream()
                        .sorted(Comparator.comparingInt(ReportSnapshot::reportVersion))
                        .map(CaseQueryService::report)
                        .toList(),
                paymentDecisionRepository.findByCasePaymentSecurityCaseId(caseId).stream()
                        .map(DecisionResponse::from)
                        .toList(),
                actionHandoffRepository.findByDecisionCasePaymentSecurityCaseId(caseId).stream()
                        .map(HandoffResponse::from)
                        .toList(),
                caseHistoryRepository.findBySecurityCaseIdOrderByCreatedAtAscIdAsc(caseId).stream()
                        .map(HistoryResponse::from)
                        .toList());
    }

    private CaseSummaryResponse summary(SecurityCase securityCase) {
        Long caseId = securityCase.getId();
        List<Optional<PaymentInfo>> payments =
                casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(caseId).stream()
                        .map(casePayment -> paymentInfoReader.find(casePayment.getPaymentId()))
                        .toList();
        return new CaseSummaryResponse(
                caseId,
                securityCase.getMerchantId(),
                securityCase.getCustomerRef(),
                securityCase.getCaseType().name(),
                securityCase.getStatus().name(),
                securityCase.getEvidenceVersion(),
                securityCase.getCreatedAt(),
                securityCase.getClosedAt(),
                payments.size(),
                totalAmount(payments),
                payments.stream()
                        .flatMap(Optional::stream)
                        .map(PaymentInfo::riskScore)
                        .filter(Objects::nonNull)
                        .max(Integer::compare)
                        .orElse(null),
                reportGateway.latest(caseId).map(ReportSnapshot::status).orElse(null),
                (int) paymentDecisionRepository.countByCasePaymentSecurityCaseId(caseId));
    }

    // 금액을 모르는 결제가 있으면 합계도 모른다 (null). 빠진 금액을 0으로 치지 않는다
    private static Long totalAmount(List<Optional<PaymentInfo>> payments) {
        long total = 0;
        for (Optional<PaymentInfo> payment : payments) {
            Long amount = payment.map(PaymentInfo::amount).orElse(null);
            if (amount == null) {
                return null;
            }
            total += amount;
        }
        return total;
    }

    private CasePaymentResponse payment(CasePayment casePayment) {
        Optional<PaymentInfo> info = paymentInfoReader.find(casePayment.getPaymentId());
        return new CasePaymentResponse(
                casePayment.getId(),
                casePayment.getPaymentId(),
                casePayment.getLinkedAt(),
                info.map(PaymentInfo::amount).orElse(null),
                info.map(PaymentInfo::paymentStatus).orElse(null),
                info.map(PaymentInfo::requestedAt).orElse(null),
                info.map(PaymentInfo::riskScore).orElse(null),
                info.map(PaymentInfo::fdsResult).orElse(null),
                info.map(PaymentInfo::ruleHits).orElse(List.of()));
    }

    private static AiReportResponse report(ReportSnapshot report) {
        return new AiReportResponse(
                report.reportId(),
                report.caseId(),
                report.reportVersion(),
                report.evidenceVersion(),
                report.status(),
                report.reportBody(),
                report.createdAt(),
                report.completedAt());
    }
}
