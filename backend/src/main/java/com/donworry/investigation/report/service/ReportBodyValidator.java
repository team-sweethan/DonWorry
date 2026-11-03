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
// 8개 섹션 + 결론(verdict, #288) · 권고 값 3종 · 보고서 속 결제가 Case 소속인지 · 다른 Case 보고서가 아닌지
@Component
@RequiredArgsConstructor
class ReportBodyValidator {

    static final Set<String> SECTIONS = Set.of(
            "overview",
            "summary",
            "paymentAnalyses",
            "timeline",
            "overallAnalysis",
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
                if (!(analysis.get("ruleHitDetails") instanceof List<?>)) {
                    errors.add("결제 " + paymentId + "의 ruleHitDetails가 목록이 아니다");
                }
            }
        } else if (body.containsKey("paymentAnalyses")) {
            errors.add("paymentAnalyses가 목록이 아니다");
        }
        errors.addAll(verdictErrors(body));
        return errors;
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
