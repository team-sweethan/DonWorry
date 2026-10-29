package com.donworry.investigation.report.service;

import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.dto.CasePaymentsToolResponse;
import com.donworry.investigation.report.dto.FdsEvidenceToolResponse;
import com.donworry.investigation.report.dto.SimilarCasesToolResponse;
import com.donworry.investigation.report.service.RuleCriterionFormatter.Criterion;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.InvestigationErrorCode;
import com.donworry.investigation.service.InvestigationException;
import com.donworry.investigation.service.PaymentInfoReader;
import com.donworry.investigation.service.PaymentInfoReader.PaymentInfo;
import com.donworry.investigation.service.PaymentInfoReader.RuleHit;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// AI 서버용 읽기 전용 Tool 조회 — Case에 묶인 결제만 내준다 (ADR-0009 결정 1 "Tool 6종")
// 결제 · FDS 값은 소유 모듈 공개 조회(PaymentInfoReader = P5 + P2)로 읽고, 못 찾은 값은 추측하지 않고 null로 둔다
@Service
@RequiredArgsConstructor
public class AiToolService {

    static final String PAYMENT_SOURCE = "P3·P5";
    static final String FDS_SOURCE = "P2";

    private final SecurityCaseRepository securityCaseRepository;
    private final CasePaymentRepository casePaymentRepository;
    private final PaymentInfoReader paymentInfoReader;
    private final SimilarCaseFinder similarCaseFinder;

    @Transactional(readOnly = true)
    public CasePaymentsToolResponse casePayments(Long caseId) {
        Scope scope = scope(caseId, List.of());
        List<CasePaymentsToolResponse.Payment> payments = scope.paymentIds().stream()
                .map(id -> {
                    PaymentInfo info = scope.infos().get(id);
                    return info == null
                            ? new CasePaymentsToolResponse.Payment(String.valueOf(id), null, null, null)
                            : new CasePaymentsToolResponse.Payment(
                                    String.valueOf(id), info.amount(), info.paymentStatus(), info.requestedAt());
                })
                .toList();
        List<String> missing = payments.stream()
                .filter(payment -> payment.amount() == null)
                .map(CasePaymentsToolResponse.Payment::paymentId)
                .toList();
        return new CasePaymentsToolResponse(
                String.valueOf(scope.caseId()),
                scope.evidenceVersion(),
                PAYMENT_SOURCE,
                scope.retrievedAt(),
                payments,
                missing);
    }

    // paymentIds를 비우면 Case에 묶인 결제 전체를 본다
    @Transactional(readOnly = true)
    public FdsEvidenceToolResponse fdsEvidence(Long caseId, Collection<Long> paymentIds) {
        Scope scope = scope(caseId, paymentIds == null ? List.of() : paymentIds);
        List<FdsEvidenceToolResponse.Payment> payments = scope.paymentIds().stream()
                .map(id -> {
                    PaymentInfo info = scope.infos().get(id);
                    return info == null
                            ? new FdsEvidenceToolResponse.Payment(String.valueOf(id), null, null, List.of())
                            : new FdsEvidenceToolResponse.Payment(
                                    String.valueOf(id),
                                    info.riskScore(),
                                    info.fdsResult(),
                                    info.ruleHits().stream()
                                            .map(AiToolService::toolRuleHit)
                                            .toList());
                })
                .toList();
        List<String> missing = payments.stream()
                .filter(payment -> payment.riskScore() == null)
                .map(FdsEvidenceToolResponse.Payment::paymentId)
                .toList();
        return new FdsEvidenceToolResponse(
                String.valueOf(scope.caseId()),
                scope.evidenceVersion(),
                FDS_SOURCE,
                scope.retrievedAt(),
                payments,
                missing);
    }

    // 기준값 · 실제값 원본 JSON은 내보내지 않고 정리한 문장만 싣는다 — 식별자가 AI 입력에 들어가지 않게 (#326)
    private static FdsEvidenceToolResponse.RuleHit toolRuleHit(RuleHit rule) {
        Optional<Criterion> criterion =
                RuleCriterionFormatter.format(rule.code(), rule.thresholdSnapshot(), rule.observedValue());
        return new FdsEvidenceToolResponse.RuleHit(
                rule.code(),
                rule.name(),
                rule.appliedScore(),
                criterion.map(Criterion::criterion).orElse(null),
                criterion.map(Criterion::observed).orElse(null));
    }

    // 비슷한 과거 사건 (#296) — 고정 보고서와 같은 규칙을 쓰도록 SimilarCaseFinder에 맡긴다 (트랜잭션도 그쪽이 연다)
    public SimilarCasesToolResponse similarCases(Long caseId) {
        return similarCaseFinder.find(caseId);
    }

    // 증거 버전과 결제 목록을 같은 읽기 트랜잭션에서 읽어 같은 시점 값으로 맞춘다
    // 요청한 결제 중 하나라도 Case 소속이 아니면 거부한다 — 다른 Case · 다른 가맹점 데이터가 새지 않게
    private Scope scope(Long caseId, Collection<Long> requested) {
        SecurityCase securityCase = securityCaseRepository
                .findById(caseId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.CASE_NOT_FOUND));
        List<Long> linked = casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(caseId).stream()
                .map(CasePayment::getPaymentId)
                .toList();
        if (!linked.containsAll(requested)) {
            throw new InvestigationException(InvestigationErrorCode.PAYMENT_NOT_IN_CASE);
        }
        List<Long> targets = requested.isEmpty()
                ? linked
                : linked.stream().filter(requested::contains).toList();
        Map<Long, PaymentInfo> infos = targets.isEmpty() ? Map.of() : paymentInfoReader.findAll(targets);
        return new Scope(
                securityCase.getId(),
                securityCase.getEvidenceVersion(),
                targets,
                infos,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private record Scope(
            Long caseId,
            int evidenceVersion,
            List<Long> paymentIds,
            Map<Long, PaymentInfo> infos,
            Instant retrievedAt) {}
}
