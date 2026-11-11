package com.donworry.investigation.report.service;

import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.dto.CasePaymentsToolResponse;
import com.donworry.investigation.report.dto.DecisionBasisToolResponse;
import com.donworry.investigation.report.dto.FdsEvidenceToolResponse;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse;
import com.donworry.investigation.report.dto.OrderContextToolResponse;
import com.donworry.investigation.report.dto.PaymentHistoryToolResponse;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// AI 서버용 읽기 전용 Tool 조회 — Case에 묶인 결제만 내준다 (ADR-0009 결정 1 "Tool 6종")
// 결제 · FDS 값은 소유 모듈 공개 조회(PaymentInfoReader = P5 + P2)로 읽고, 못 찾은 값은 추측하지 않고 null로 둔다
@Service
@Slf4j
@RequiredArgsConstructor
public class AiToolService {

    static final String PAYMENT_SOURCE = "P3·P5";
    static final String FDS_SOURCE = "P2";

    private final SecurityCaseRepository securityCaseRepository;
    private final CasePaymentRepository casePaymentRepository;
    private final PaymentInfoReader paymentInfoReader;
    private final SimilarCaseFinder similarCaseFinder;
    private final PaymentHistoryFinder paymentHistoryFinder;
    private final PaymentSignalReader paymentSignalReader;
    private final LinkedPaymentFinder linkedPaymentFinder;
    private final OrderContextFinder orderContextFinder;

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
        // 카드 · 기기 · 접속 국가는 결제 ID로 짝을 맞춘다 — 평가가 없는 결제는 null (#346)
        Map<Long, FdsEvidenceToolResponse.Signals> signals = paymentSignalReader.findAll(scope.paymentIds());
        List<FdsEvidenceToolResponse.Payment> payments = scope.paymentIds().stream()
                .map(id -> {
                    PaymentInfo info = scope.infos().get(id);
                    return info == null
                            ? new FdsEvidenceToolResponse.Payment(
                                    String.valueOf(id), null, null, List.of(), signals.get(id))
                            : new FdsEvidenceToolResponse.Payment(
                                    String.valueOf(id),
                                    info.riskScore(),
                                    info.fdsResult(),
                                    info.ruleHits().stream()
                                            .map(AiToolService::toolRuleHit)
                                            .toList(),
                                    signals.get(id));
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
    // 고정 보고서도 같은 모양으로 징후 · 정상 근거를 계산하려고 같은 패키지에 연다 (#618)
    static FdsEvidenceToolResponse.RuleHit toolRuleHit(RuleHit rule) {
        Optional<Criterion> criterion =
                RuleCriterionFormatter.format(rule.code(), rule.thresholdSnapshot(), rule.observedValue());
        return FdsEvidenceToolResponse.RuleHit.builder()
                .code(rule.code())
                .name(rule.name())
                .appliedScore(rule.appliedScore())
                .criterion(criterion.map(Criterion::criterion).orElse(null))
                .observed(criterion.map(Criterion::observed).orElse(null))
                .build();
    }

    // 비슷한 과거 사건 (#296) — 고정 보고서와 같은 규칙을 쓰도록 SimilarCaseFinder에 맡긴다 (트랜잭션도 그쪽이 연다)
    public SimilarCasesToolResponse similarCases(Long caseId) {
        return similarCaseFinder.find(caseId);
    }

    // 고객 최근 결제 (#338) — 고정 보고서와 같은 규칙을 쓰도록 PaymentHistoryFinder에 맡긴다 (트랜잭션도 그쪽이 연다)
    // 기간은 30일(기본 조회) 또는 90일(AI 추가 조사, #581) — 검사도 PaymentHistoryFinder가 한다
    public PaymentHistoryToolResponse paymentHistory(Long caseId, int lookbackDays) {
        return paymentHistoryFinder.find(caseId, Instant.now().truncatedTo(ChronoUnit.MICROS), lookbackDays);
    }

    // 같은 카드 · 기기 · IP를 쓴 다른 고객 결제 (#352) — 고정 보고서와 같은 규칙을 쓰도록 LinkedPaymentFinder에 맡긴다
    // 앞뒤 시간은 1시간(보고서 기본) 또는 6 · 24시간(질문 넓혀 보기, #751) — 검사도 LinkedPaymentFinder가 한다
    public LinkedPaymentsToolResponse linkedPayments(Long caseId, int nearbyHours) {
        return linkedPaymentFinder.find(caseId, Instant.now().truncatedTo(ChronoUnit.MICROS), nearbyHours);
    }

    // 사건 결제의 주문 정보 (#634) — 고정 보고서와 같은 규칙을 쓰도록 OrderContextFinder에 맡긴다
    public OrderContextToolResponse orderContext(Long caseId) {
        return orderContextFinder.find(caseId, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    // 결제별 징후 · 정상 근거 (#618) — Spring이 계산하고 ai-server는 그대로 싣는다 (ADR-0009 결정 7 · 13)
    // 고객 이력은 늘 30일 기본 조회로 본다 — AI가 90일을 불렀는지와 상관없이 같은 사건엔 같은 권고 (#581)
    // 고객 이력 조회가 실패하면 정상 사유 없이 계산한다 (권고가 매입 진행 쪽으로 올라가지 않는다)
    // 연결 결제 조회가 실패하면 근거를 주지 않는다 — 고위험 징후를 확인하지 못했다 (#618 Codex 리뷰, DecisionBasisCalculator.forCase)
    // 트랜잭션을 열지 않는다 — 참고 조회 실패가 감싼 읽기 트랜잭션을 rollback-only로 만들지 않게 조회마다 따로 읽는다
    public DecisionBasisToolResponse decisionBasis(Long caseId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        CasePaymentsToolResponse cases = casePayments(caseId);
        FdsEvidenceToolResponse fds = fdsEvidence(caseId, List.of());
        PaymentHistoryToolResponse history = null;
        try {
            history = paymentHistoryFinder.find(caseId, now, PaymentHistoryFinder.DEFAULT_LOOKBACK_DAYS);
        } catch (RuntimeException e) {
            log.warn("징후 계산용 고객 최근 결제 조회 실패 caseId={}", caseId, e);
        }
        LinkedPaymentsToolResponse linked = null;
        try {
            linked = linkedPaymentFinder.find(caseId, now);
        } catch (RuntimeException e) {
            log.warn("징후 계산용 같은 카드 · 기기 · IP 결제 조회 실패 caseId={}", caseId, e);
        }
        return DecisionBasisCalculator.forCase(cases, fds, history, linked, now);
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
