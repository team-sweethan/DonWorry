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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final CaseStatusService caseStatusService;

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
        // 고객이 직접 승인을 취소한 결제(#382 · #439) — 직원 거절과 구분해 보인다
        Set<Long> customerCanceled =
                caseStatusService.canceledCasePayments(caseId).keySet();
        return MerchantCaseDetailResponse.builder()
                .caseId(securityCase.getId())
                .displayStatus(displayStatus(securityCase.getStatus()))
                .createdAt(securityCase.getCreatedAt())
                .closedAt(securityCase.getClosedAt())
                .summary(MerchantCasePublicSummary.forStatus(securityCase.getStatus()))
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
                                    .customerCanceled(customerCanceled.contains(casePayment.getId()))
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
}
