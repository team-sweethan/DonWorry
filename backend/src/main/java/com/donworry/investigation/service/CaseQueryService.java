package com.donworry.investigation.service;

import com.donworry.investigation.dto.CaseCountsResponse;
import com.donworry.investigation.dto.CaseDetailResponse;
import com.donworry.investigation.dto.CaseDetailResponse.AiReportResponse;
import com.donworry.investigation.dto.CaseDetailResponse.CasePaymentResponse;
import com.donworry.investigation.dto.CaseDetailResponse.DecisionResponse;
import com.donworry.investigation.dto.CaseDetailResponse.HandoffResponse;
import com.donworry.investigation.dto.CaseDetailResponse.HistoryResponse;
import com.donworry.investigation.dto.CaseDetailResponse.RuleHitResponse;
import com.donworry.investigation.dto.CaseDetailResponse.SecurityCaseResponse;
import com.donworry.investigation.dto.CasePaymentLinkResponse;
import com.donworry.investigation.dto.CaseSummaryResponse;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseStatus;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.ActionHandoffRepository;
import com.donworry.investigation.repository.CaseHistoryRepository;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.CaseStatusCount;
import com.donworry.investigation.repository.PaymentDecisionRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.PaymentInfoReader.PaymentInfo;
import com.donworry.investigation.service.ReportGateway.ReportSnapshot;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 보안 직원 화면용 Case 조회 — P3 데이터에 P5 결제 · P2 평가 · P4 보고서를 합친다
@Service
@RequiredArgsConstructor
public class CaseQueryService {

    // 경보 목록 한 페이지를 한 번에 조회할 수 있는 만큼 (#309)
    static final int MAX_PAYMENT_LINK_IDS = 100;

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

    // 사이드바 배지용 — 목록과 달리 Case마다 결제 · FDS · 보고서를 조회하지 않는다 (#224)
    // 화면이 키 유무를 따지지 않게 없는 상태도 0으로 채운다
    @Transactional(readOnly = true)
    public CaseCountsResponse counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (CaseStatus status : CaseStatus.values()) {
            counts.put(status.name(), 0L);
        }
        for (CaseStatusCount row : securityCaseRepository.countByStatus()) {
            counts.put(row.status().name(), row.count());
        }
        return new CaseCountsResponse(counts);
    }

    // 결제 ID → 연결된 Case (#309) — 경보 화면이 Case로 이동할 때 쓴다. P2는 P3를 부를 수 없어 화면이 이 API를 부른다
    // 대부분의 경보(ALLOW · 승인 거절 · DENY)는 Case가 없으므로 없는 결제는 오류 없이 빼고 보낸다
    @Transactional(readOnly = true)
    public List<CasePaymentLinkResponse> paymentLinks(List<Long> paymentIds) {
        List<Long> ids = paymentIds == null
                ? List.of()
                : paymentIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty() || ids.size() > MAX_PAYMENT_LINK_IDS) {
            throw new InvestigationException(InvestigationErrorCode.PAYMENT_IDS_OUT_OF_RANGE);
        }
        return casePaymentRepository.findLinksByPaymentIdIn(ids).stream()
                .map(link -> new CasePaymentLinkResponse(
                        link.paymentId(), link.caseId(), link.caseStatus().name()))
                .toList();
    }

    @Transactional(readOnly = true)
    public CaseDetailResponse detail(Long caseId) {
        SecurityCase securityCase = securityCaseRepository
                .findById(caseId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.CASE_NOT_FOUND));
        List<CasePayment> casePayments = casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(caseId);
        Map<Long, PaymentInfo> infos = paymentInfos(casePayments);
        return new CaseDetailResponse(
                SecurityCaseResponse.from(securityCase, InvestigationRules.mergeDeadline(securityCase.getCreatedAt())),
                casePayments.stream()
                        .map(casePayment -> payment(casePayment, infos.get(casePayment.getPaymentId())))
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
        List<CasePayment> casePayments = casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(caseId);
        Map<Long, PaymentInfo> infos = paymentInfos(casePayments);
        List<Optional<PaymentInfo>> payments = casePayments.stream()
                .map(casePayment -> Optional.ofNullable(infos.get(casePayment.getPaymentId())))
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
                maxRiskScore(payments),
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

    // 점수를 모르는 결제가 있으면 최고 점수도 모른다 (null). 남은 결제만으로 낮게 보이게 하지 않는다
    private static Integer maxRiskScore(List<Optional<PaymentInfo>> payments) {
        Integer max = null;
        for (Optional<PaymentInfo> payment : payments) {
            Integer riskScore = payment.map(PaymentInfo::riskScore).orElse(null);
            if (riskScore == null) {
                return null;
            }
            max = max == null ? riskScore : Math.max(max, riskScore);
        }
        return max;
    }

    // Case 하나의 결제를 한 번에 조회한다 — P2 평가 근거는 결제 ID 목록으로 한 번에 읽는다
    private Map<Long, PaymentInfo> paymentInfos(List<CasePayment> casePayments) {
        return paymentInfoReader.findAll(
                casePayments.stream().map(CasePayment::getPaymentId).toList());
    }

    private static CasePaymentResponse payment(CasePayment casePayment, PaymentInfo found) {
        Optional<PaymentInfo> info = Optional.ofNullable(found);
        return new CasePaymentResponse(
                casePayment.getId(),
                casePayment.getPaymentId(),
                casePayment.getLinkedAt(),
                info.map(PaymentInfo::amount).orElse(null),
                info.map(PaymentInfo::paymentStatus).orElse(null),
                info.map(PaymentInfo::requestedAt).orElse(null),
                info.map(PaymentInfo::riskScore).orElse(null),
                info.map(PaymentInfo::fdsResult).orElse(null),
                info.map(PaymentInfo::ruleHits).orElse(List.of()).stream()
                        .map(rule -> new RuleHitResponse(rule.code(), rule.name(), rule.appliedScore()))
                        .toList());
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
