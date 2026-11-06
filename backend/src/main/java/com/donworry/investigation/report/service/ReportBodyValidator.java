package com.donworry.investigation.report.service;

import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.report.entity.AiReport;
import com.donworry.investigation.repository.CasePaymentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// ai-server 보고서 본문을 저장 전에 검사한다 (ADR-0009 결정 4) — 걸리면 시도 실패 1회
// 필수 섹션 + 결론(verdict, #288) · 권고 값 3종 · 보고서 속 결제가 Case 소속인지 · 다른 Case 보고서가 아닌지
// 종합 분석(overallAnalysis) 대신 사실 번호 목록(facts, #548)과 요약 근거 번호(summaryFacts, #559)를 받는다
@Component
@RequiredArgsConstructor
class ReportBodyValidator {

    static final Set<String> SECTIONS = Set.of(
            "overview",
            "summary",
            "summaryFacts",
            "paymentAnalyses",
            "timeline",
            "facts",
            "additionalChecks",
            "recommendation",
            "evidence",
            "verdict");
    static final Set<String> RECOMMENDATIONS = Set.of("ACCEPT_REVIEW", "REJECT_REVIEW", "NEEDS_CHECK");

    private final CasePaymentRepository casePaymentRepository;

    @Transactional(readOnly = true)
    public List<String> validate(AiReport report, Map<String, Object> body) {
        List<String> errors = new ArrayList<>();
        if (body == null) {
            return List.of("보고서 본문이 없다");
        }
        Set<String> missing =
                SECTIONS.stream().filter(section -> !body.containsKey(section)).collect(Collectors.toSet());
        if (!missing.isEmpty()) {
            errors.add("빠진 섹션: " + missing);
        }
        if (!(body.get("overview") instanceof Map<?, ?> overview)
                || !String.valueOf(report.getCaseId()).equals(String.valueOf(overview.get("caseId")))) {
            errors.add("사건 개요의 caseId가 이 보고서의 Case가 아니다");
        }
        Set<String> linked = casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(report.getCaseId()).stream()
                .map(CasePayment::getPaymentId)
                .map(String::valueOf)
                .collect(Collectors.toSet());
        if (body.get("paymentAnalyses") instanceof List<?> analyses) {
            for (Object item : analyses) {
                if (!(item instanceof Map<?, ?> analysis)) {
                    errors.add("결제별 분석 형식이 아니다");
                    continue;
                }
                String paymentId = String.valueOf(analysis.get("paymentId"));
                if (!linked.contains(paymentId)) {
                    errors.add("Case 소속이 아닌 결제: " + paymentId);
                }
                if (!RECOMMENDATIONS.contains(String.valueOf(analysis.get("recommendation")))) {
                    errors.add("허용되지 않은 권고 값: " + analysis.get("recommendation"));
                }
                errors.addAll(basisErrors(paymentId, analysis));
                if (!(analysis.get("ruleHitDetails") instanceof List<?>)) {
                    errors.add("결제 " + paymentId + "의 ruleHitDetails가 목록이 아니다");
                }
            }
        } else if (body.containsKey("paymentAnalyses")) {
            errors.add("paymentAnalyses가 목록이 아니다");
        }
        errors.addAll(verdictErrors(body));
        errors.addAll(overallErrors(body));
        return errors;
    }

    // 종합 권고는 결제별 권고로 정해진다 (ADR-0009 결정 4) — 결제별 권고와 다른 종합 권고가 저장되지 않게 (#618)
    private static List<String> overallErrors(Map<String, Object> body) {
        if (!(body.get("paymentAnalyses") instanceof List<?> analyses)
                || !(body.get("verdict") instanceof Map<?, ?> verdict)) {
            return List.of();
        }
        List<String> recommendations = analyses.stream()
                .filter(Map.class::isInstance)
                .map(item -> String.valueOf(((Map<?, ?>) item).get("recommendation")))
                .toList();
        String expected = DecisionBasisCalculator.overall(recommendations);
        Object overall = verdict.get("overallRecommendation");
        return expected.equals(overall)
                ? List.of()
                : List.of("종합 권고(" + overall + ")가 결제별 권고로 정한 값(" + expected + ")과 다르다");
    }

    // 판정 근거가 있는 결제는 권고가 기준표 칸의 권고와 같아야 한다 (#618)
    // 기준표는 Spring이 계산해 ai-server에 준다 — 보고서에서 권고만 다른 값으로 바뀌어 저장되지 않게
    // 판정 근거가 없는 결제(기준표를 받지 못함)는 추가 확인만 허용한다 — 근거 없이 자동으로 승인 취소 · 매입 진행 쪽으로 가지 않게
    private static List<String> basisErrors(String paymentId, Map<?, ?> analysis) {
        Object recommendation = analysis.get("recommendation");
        if (!(analysis.get("basis") instanceof Map<?, ?> basis)) {
            return analysis.get("basis") == null && !DecisionBasisCalculator.NEEDS_CHECK.equals(recommendation)
                    ? List.of("결제 " + paymentId + "는 판정 근거 없이 권고가 " + recommendation + "이다")
                    : List.of();
        }
        String expected = DecisionBasisCalculator.RECOMMENDATION_BY_RULE.get(String.valueOf(basis.get("rule")));
        if (expected == null) {
            return List.of("결제 " + paymentId + "의 기준표 칸이 허용 밖이다: " + basis.get("rule"));
        }
        if (!expected.equals(basis.get("recommendation")) || !expected.equals(recommendation)) {
            return List.of("결제 " + paymentId + "의 권고가 기준표 칸(" + basis.get("rule") + ")과 다르다: " + recommendation);
        }
        return List.of();
    }

    // 결론 카드 값 — 결론 한 줄이 있고 종합 권고가 허용된 값인지 본다
    // 키가 빠진 경우는 SECTIONS가 알리고, "verdict": null처럼 값이 비었거나 객체가 아니면 여기서 거부한다
    private static List<String> verdictErrors(Map<String, Object> body) {
        if (!body.containsKey("verdict")) {
            return List.of();
        }
        if (!(body.get("verdict") instanceof Map<?, ?> verdict)) {
            return List.of("verdict가 비었거나 객체가 아니다");
        }
        List<String> errors = new ArrayList<>();
        if (!(verdict.get("headline") instanceof String headline) || headline.isBlank()) {
            errors.add("결론 한 줄(verdict.headline)이 비어 있다");
        }
        if (!RECOMMENDATIONS.contains(String.valueOf(verdict.get("overallRecommendation")))) {
            errors.add("허용되지 않은 종합 권고 값: " + verdict.get("overallRecommendation"));
        }
        return errors;
    }
}
