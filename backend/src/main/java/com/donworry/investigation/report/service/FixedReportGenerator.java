package com.donworry.investigation.report.service;

import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.dto.SimilarCasesToolResponse;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.PaymentInfoReader;
import com.donworry.investigation.service.PaymentInfoReader.PaymentInfo;
import com.donworry.investigation.service.PaymentInfoReader.RuleHit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 임시 — FastAPI 연결(P4-2) 전까지 Case 결제 근거로 고정 문장 보고서를 만든다. 이전 MockReportGateway 본문을 그대로 옮겼다
// report_body 키는 화면 ReportBody(frontend/src/types/case.ts · ADR-0009 결정 4)를 따른다
@Component
@Slf4j
@RequiredArgsConstructor
class FixedReportGenerator {

    private final SecurityCaseRepository securityCaseRepository;
    private final CasePaymentRepository casePaymentRepository;
    private final PaymentInfoReader paymentInfoReader;
    private final SimilarCaseFinder similarCaseFinder;

    // 연결 결제 중 하나라도 조회하지 못하거나 금액 · 요청 시각 · 점수가 비면 빈 값 — 증거가 빠진 보고서를 판정 근거로 쓰지 않는다
    // 조회 오류는 숨기지 않고 던진다 — 호출자가 시도 실패로 센다
    // 커밋 뒤 리스너에서 불리므로 끝난 트랜잭션에 합류하지 않게 새 읽기 트랜잭션을 연다
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<Map<String, Object>> generate(Long caseId, int reportVersion, int evidenceVersion, Instant now) {
        SecurityCase securityCase = securityCaseRepository.findById(caseId).orElseThrow();
        List<Long> paymentIds = casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(caseId).stream()
                .map(CasePayment::getPaymentId)
                .toList();
        Map<Long, PaymentInfo> found = paymentInfoReader.findAll(paymentIds);
        List<PaymentInfo> payments = paymentIds.stream()
                .map(found::get)
                .filter(FixedReportGenerator::hasEvidence)
                .toList();
        if (payments.size() != paymentIds.size()) {
            return Optional.empty();
        }
        return Optional.of(body(securityCase, payments, similarOrNull(caseId), reportVersion, evidenceVersion, now));
    }

    // 비슷한 사건은 참고 자료라 조회에 실패해도 보고서는 만든다 — 값 없음(null)으로 남겨 화면이 "조회하지 못함"을 보여 준다
    private SimilarCasesToolResponse similarOrNull(Long caseId) {
        try {
            return similarCaseFinder.find(caseId);
        } catch (RuntimeException e) {
            log.warn("비슷한 과거 사건 조회 실패 caseId={}", caseId, e);
            return null;
        }
    }

    private static boolean hasEvidence(PaymentInfo payment) {
        return payment != null
                && payment.amount() != null
                && payment.requestedAt() != null
                && payment.riskScore() != null;
    }

    private static Map<String, Object> body(
            SecurityCase securityCase,
            List<PaymentInfo> payments,
            SimilarCasesToolResponse similar,
            int reportVersion,
            int evidenceVersion,
            Instant now) {
        long totalAmount = payments.stream().mapToLong(PaymentInfo::amount).sum();
        String dataVersion = "evidence v" + evidenceVersion;
        List<Map<String, Object>> analyses = new ArrayList<>();
        List<Map<String, Object>> timeline = new ArrayList<>();
        String fdsEvidenceId = evidenceId(securityCase.getId(), reportVersion, 2);
        for (PaymentInfo payment : payments) {
            // 정상 근거는 비워 둔다 — 고정 보고서는 결제 · FDS 근거 외에 확인한 것이 없다 (#288)
            analyses.add(Map.ofEntries(
                    Map.entry("paymentId", payment.paymentId()),
                    Map.entry("amount", payment.amount()),
                    Map.entry("requestedAt", payment.requestedAt().toString()),
                    Map.entry("riskScore", payment.riskScore()),
                    Map.entry(
                            "ruleHits",
                            payment.ruleHits().stream().map(RuleHit::code).toList()),
                    Map.entry(
                            "riskEvidence",
                            payment.ruleHits().stream()
                                    .map(rule -> rule.name() + " 규칙 적중")
                                    .toList()),
                    Map.entry("normalEvidence", List.of()),
                    Map.entry("unknowns", List.of("고객 본인의 결제 의사")),
                    Map.entry("recommendation", "NEEDS_CHECK"),
                    Map.entry("reason", "규칙 적중 외에 판단할 근거가 없어 추가 확인이 필요합니다."),
                    Map.entry(
                            "ruleHitDetails",
                            payment.ruleHits().stream()
                                    .map(rule -> ruleHitDetail(rule, fdsEvidenceId))
                                    .toList())));
            timeline.add(Map.of(
                    "at",
                    payment.requestedAt().toString(),
                    "event",
                    payment.paymentId() + " 결제 요청 · FDS " + payment.fdsResult() + " (" + payment.riskScore() + "점)"));
        }
        Map<String, Object> body = new HashMap<>(Map.ofEntries(
                Map.entry(
                        "overview",
                        Map.of(
                                "caseId", securityCase.getId(),
                                "merchantId", securityCase.getMerchantId(),
                                "customerRef", securityCase.getCustomerRef(),
                                "paymentCount", payments.size(),
                                "totalAmount", totalAmount,
                                "caseCreatedAt", securityCase.getCreatedAt().toString(),
                                "investigatedAt", now.toString(),
                                "reportVersion", reportVersion)),
                Map.entry(
                        "summary",
                        List.of(
                                "조사 대상 결제 " + payments.size() + "건에서 FDS 규칙 적중이 확인되었습니다.",
                                "직원은 결제별 위험 근거와 정상 정황을 비교한 뒤 판정해 주세요.")),
                Map.entry("paymentAnalyses", analyses),
                Map.entry("timeline", timeline),
                Map.entry("overallAnalysis", List.of("결제 간 공통 기기·계정 관계는 확인되지 않았습니다.")),
                Map.entry("additionalChecks", List.of("고객 본인 확인 필요 여부")),
                Map.entry(
                        "recommendation",
                        Map.of(
                                "summary",
                                "추가 확인 후 결제별로 판정할 것을 권고합니다.",
                                "limitations",
                                List.of("고정 문장 보고서입니다(AI 연결 전). FDS 점수는 부정거래 확률이 아닙니다."))),
                Map.entry(
                        "evidence",
                        List.of(
                                evidence(
                                        "get_case_payments",
                                        "P3·P5",
                                        securityCase.getId(),
                                        reportVersion,
                                        1,
                                        now,
                                        dataVersion),
                                evidence(
                                        "get_fds_evidence",
                                        "P2",
                                        securityCase.getId(),
                                        reportVersion,
                                        2,
                                        now,
                                        dataVersion),
                                evidence(
                                        "find_similar_cases",
                                        SimilarCaseFinder.SOURCE,
                                        securityCase.getId(),
                                        reportVersion,
                                        3,
                                        now,
                                        dataVersion))),
                Map.entry(
                        "verdict",
                        Map.of(
                                "headline",
                                "FDS 규칙에 걸린 결제라 고객 본인 확인 후 판정이 필요합니다.",
                                "overallRecommendation",
                                "NEEDS_CHECK")),
                Map.entry("investigationTrace", fixedTrace(payments.size(), similar))));
        body.put("similarCases", similar == null ? null : similarCases(similar));
        return body;
    }

    // 비슷한 과거 사건 (#296) — ai-server와 같은 키. 사실 값만 옮기고 해석하지 않는다
    private static Map<String, Object> similarCases(SimilarCasesToolResponse similar) {
        return Map.of(
                "cases",
                similar.cases().stream()
                        .map(c -> Map.<String, Object>of(
                                "caseId", c.caseId(),
                                "similarity", c.similarity(),
                                "sharedRules",
                                        c.sharedRules().stream()
                                                .map(r -> Map.of("code", r.code(), "name", r.name()))
                                                .toList(),
                                "closedAt", String.valueOf(c.closedAt()),
                                "acceptCount", c.acceptCount(),
                                "rejectCount", c.rejectCount()))
                        .toList(),
                "totalMatched",
                similar.totalMatched());
    }

    // 조사 과정 기록 (#294) — AI가 조사하지 않았으므로 FIXED와 시스템 단계만 남긴다. 키는 ai-server InvestigationTrace와 같다
    private static Map<String, Object> fixedTrace(int paymentCount, SimilarCasesToolResponse similar) {
        Map<String, Object> trace = new HashMap<>();
        trace.put("provider", "FIXED");
        trace.put("model", "fixed");
        trace.put("durationMs", null);
        trace.put(
                "steps",
                List.of(
                        traceStep(
                                "TOOL",
                                "결제 · FDS 근거 조회",
                                "이 사건의 결제 전체",
                                "결제 " + paymentCount + "건 · FDS 평가 " + paymentCount + "건"),
                        traceStep(
                                "TOOL",
                                "비슷한 과거 사건 조회",
                                "이 사건과 비슷한 종료 사건",
                                similar == null ? "조회 실패" : "비슷한 사건 " + similar.totalMatched() + "건"),
                        traceStep("ASSEMBLE", "고정 문장 보고서 조립", null, "AI 서버가 꺼져 정해진 문장으로 조립")));
        return trace;
    }

    // 값이 없는 칸(null)이 있어 Map.of 대신 HashMap을 쓴다
    private static Map<String, Object> traceStep(String kind, String title, String asked, String received) {
        Map<String, Object> step = new HashMap<>();
        step.put("kind", kind);
        step.put("actor", "SYSTEM");
        step.put("title", title);
        step.put("durationMs", null);
        step.put("asked", asked);
        step.put("received", received);
        step.put("retryReason", null);
        return step;
    }

    // appliedScore가 없을 수 있어 Map.of 대신 null을 받는 HashMap을 쓴다
    private static Map<String, Object> ruleHitDetail(RuleHit rule, String evidenceId) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("code", rule.code());
        detail.put("name", rule.name());
        detail.put("appliedScore", rule.appliedScore());
        detail.put("evidenceId", evidenceId);
        return detail;
    }

    private static String evidenceId(Long caseId, int reportVersion, int seq) {
        return "E-" + caseId + "-v" + reportVersion + "-0" + seq;
    }

    private static Map<String, Object> evidence(
            String toolName, String source, Long caseId, int reportVersion, int seq, Instant now, String dataVersion) {
        return Map.of(
                "toolName", toolName,
                "source", source,
                "evidenceId", evidenceId(caseId, reportVersion, seq),
                "retrievedAt", now.toString(),
                "dataVersion", dataVersion);
    }
}
