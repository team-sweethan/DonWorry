package com.donworry.investigation.report.service;

import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.report.dto.FdsEvidenceToolResponse.Signals;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse;
import com.donworry.investigation.report.dto.PaymentHistoryToolResponse;
import com.donworry.investigation.report.dto.SimilarCasesToolResponse;
import com.donworry.investigation.report.service.RuleCriterionFormatter.Criterion;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.PaymentInfoReader;
import com.donworry.investigation.service.PaymentInfoReader.PaymentInfo;
import com.donworry.investigation.service.PaymentInfoReader.RuleHit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

    // AI가 조사하지 않는 고정 보고서라 "AI 조사 시작"은 남기지 않는다 (#310 리뷰)
    static final String CASE_CREATED_EVENT = "사건 자동 생성";
    // 화면에 코드값 대신 한국어로 — ai-server assemble.py와 같은 이름
    private static final Map<String, String> FDS_RESULT_KO =
            Map.of("ALLOW", "허용", "REVIEW", "검토", "DENY", "차단", "ERROR", "평가 오류");
    private static final Map<String, String> PAYMENT_STATUS_KO = Map.of(
            "PENDING", "처리 중",
            "HELD", "매입 보류",
            "CAPTURED", "매입 완료",
            "VOIDED", "승인 취소",
            "DENIED", "자동 차단",
            "DECLINED", "카드사 거절",
            "EXPIRED", "만료");

    private final SecurityCaseRepository securityCaseRepository;
    private final CasePaymentRepository casePaymentRepository;
    private final PaymentInfoReader paymentInfoReader;
    private final SimilarCaseFinder similarCaseFinder;
    private final PaymentHistoryFinder paymentHistoryFinder;
    private final PaymentSignalReader paymentSignalReader;
    private final LinkedPaymentFinder linkedPaymentFinder;

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
        return Optional.of(body(
                securityCase,
                payments,
                paymentSignalReader.findAll(paymentIds),
                similarOrNull(caseId),
                historyOrNull(caseId, now),
                linkedOrNull(caseId, now),
                reportVersion,
                evidenceVersion,
                now));
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

    // 고객 최근 결제도 참고 자료라 조회에 실패해도 보고서는 만든다 (#338)
    private PaymentHistoryToolResponse historyOrNull(Long caseId, Instant now) {
        try {
            return paymentHistoryFinder.find(caseId, now);
        } catch (RuntimeException e) {
            log.warn("고객 최근 결제 조회 실패 caseId={}", caseId, e);
            return null;
        }
    }

    // 같은 카드 · 기기 · IP를 쓴 다른 결제도 참고 자료라 조회에 실패해도 보고서는 만든다 (#352)
    private LinkedPaymentsToolResponse linkedOrNull(Long caseId, Instant now) {
        try {
            return linkedPaymentFinder.find(caseId, now);
        } catch (RuntimeException e) {
            log.warn("같은 카드 · 기기 · IP 결제 조회 실패 caseId={}", caseId, e);
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
            Map<Long, Signals> signals,
            SimilarCasesToolResponse similar,
            PaymentHistoryToolResponse history,
            LinkedPaymentsToolResponse linked,
            int reportVersion,
            int evidenceVersion,
            Instant now) {
        long totalAmount = payments.stream().mapToLong(PaymentInfo::amount).sum();
        String dataVersion = "evidence v" + evidenceVersion;
        List<Map<String, Object>> analyses = new ArrayList<>();
        String fdsEvidenceId = evidenceId(securityCase.getId(), reportVersion, 2);
        for (PaymentInfo payment : payments) {
            // 정상 근거는 비워 둔다 — 고정 보고서는 결제 · FDS 근거 외에 확인한 것이 없다 (#288)
            Map<String, Object> analysis = new HashMap<>(Map.ofEntries(
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
            // 카드 · 기기 · 접속 국가 (#346) — ai-server와 같은 키. 평가가 없으면 null
            analysis.put("signals", signalsOf(signals.get(payment.paymentId())));
            analyses.add(analysis);
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
                Map.entry("timeline", timeline(securityCase, payments)),
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
                        evidenceList(securityCase.getId(), similar, history, linked, reportVersion, now, dataVersion)),
                Map.entry(
                        "verdict",
                        Map.of(
                                "headline",
                                "FDS 규칙에 걸린 결제라 고객 본인 확인 후 판정이 필요합니다.",
                                "overallRecommendation",
                                "NEEDS_CHECK")),
                Map.entry("investigationTrace", fixedTrace(payments.size(), similar, history, linked))));
        body.put("similarCases", similar == null ? null : similarCases(similar));
        body.put("paymentHistory", history == null ? null : paymentHistory(history));
        body.put("linkedPayments", linked == null ? null : linkedPayments(linked));
        return body;
    }

    // 사건 경위 (#310) — ai-server와 같은 모양. 결제 줄 → 사건 생성 줄을 실제 시각순으로, 같은 시각이면 결제 먼저 (안정 정렬)
    private static List<Map<String, Object>> timeline(SecurityCase securityCase, List<PaymentInfo> payments) {
        record Line(Instant at, String event, String kind) {}
        List<Line> lines = new ArrayList<>();
        for (PaymentInfo payment : payments) {
            lines.add(new Line(payment.requestedAt(), paymentEvent(payment), "PAYMENT"));
        }
        lines.add(new Line(securityCase.getCreatedAt(), CASE_CREATED_EVENT, "CASE"));
        lines.sort(Comparator.comparing(Line::at));
        return lines.stream()
                .map(line ->
                        Map.<String, Object>of("at", line.at().toString(), "event", line.event(), "kind", line.kind()))
                .toList();
    }

    // "결제 15 · 600,000원 → FDS 검토 60점 → 매입 보류" — 결제 상태가 없거나 모르는 값이면 추측하지 않고 뺀다
    private static String paymentEvent(PaymentInfo payment) {
        String fdsResult =
                payment.fdsResult() == null ? "판정 미확인" : FDS_RESULT_KO.getOrDefault(payment.fdsResult(), "판정 미확인");
        String event = "결제 " + payment.paymentId() + " · " + String.format(Locale.ROOT, "%,d원", payment.amount())
                + " → FDS " + fdsResult + " " + payment.riskScore() + "점";
        String status = payment.paymentStatus() == null ? null : PAYMENT_STATUS_KO.get(payment.paymentStatus());
        return status == null ? event : event + " → " + status;
    }

    // 증거 추적 줄 — 비슷한 사건 · 고객 최근 결제는 조회에 성공했을 때만, 실제 응답의 출처 · 조회 시각 · 증거 버전으로 남긴다 (#296 리뷰)
    private static List<Map<String, Object>> evidenceList(
            Long caseId,
            SimilarCasesToolResponse similar,
            PaymentHistoryToolResponse history,
            LinkedPaymentsToolResponse linked,
            int reportVersion,
            Instant now,
            String dataVersion) {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(evidence("get_case_payments", "P3·P5", caseId, reportVersion, 1, now, dataVersion));
        list.add(evidence("get_fds_evidence", "P2", caseId, reportVersion, 2, now, dataVersion));
        if (similar != null) {
            list.add(evidence(
                    "find_similar_cases",
                    similar.source(),
                    caseId,
                    reportVersion,
                    3,
                    similar.retrievedAt(),
                    "evidence v" + similar.evidenceVersion()));
        }
        if (history != null) {
            list.add(evidence(
                    "get_payment_history",
                    history.source(),
                    caseId,
                    reportVersion,
                    4,
                    history.retrievedAt(),
                    "evidence v" + history.evidenceVersion()));
        }
        if (linked != null) {
            list.add(evidence(
                    "find_linked_payments",
                    linked.source(),
                    caseId,
                    reportVersion,
                    5,
                    linked.retrievedAt(),
                    "evidence v" + linked.evidenceVersion()));
        }
        return list;
    }

    // 값이 없는 칸(null)이 있어 Map.of 대신 HashMap을 쓴다
    private static Map<String, Object> signalsOf(Signals s) {
        if (s == null) {
            return null;
        }
        Map<String, Object> map = new HashMap<>();
        map.put("cardDisplayLabel", s.cardDisplayLabel());
        map.put("cardProvided", s.cardProvided());
        map.put("deviceProvided", s.deviceProvided());
        map.put("ipCountryCode", s.ipCountryCode());
        map.put("ipProvided", s.ipProvided());
        return map;
    }

    // 고객 최근 결제 (#338) — ai-server와 같은 키. 사실 값만 옮기고 해석하지 않는다
    private static Map<String, Object> paymentHistory(PaymentHistoryToolResponse history) {
        return Map.of(
                "lookbackDays", history.lookbackDays(),
                "payments",
                        history.payments().stream()
                                .map(p -> {
                                    // 결제 정보가 비어 있을 수 있어 null을 받는 HashMap을 쓴다
                                    Map<String, Object> payment = new HashMap<>();
                                    payment.put("paymentId", p.paymentId());
                                    payment.put("amount", p.amount());
                                    payment.put("paymentStatus", p.paymentStatus());
                                    payment.put(
                                            "requestedAt",
                                            p.requestedAt() == null
                                                    ? null
                                                    : p.requestedAt().toString());
                                    return payment;
                                })
                                .toList(),
                "statusCounts",
                        history.statusCounts().stream()
                                .map(c ->
                                        Map.<String, Object>of("paymentStatus", c.paymentStatus(), "count", c.count()))
                                .toList(),
                "truncated", history.truncated());
    }

    // 같은 카드 · 기기 · IP를 쓴 다른 결제 (#352) — ai-server와 같은 키. 숫자만 옮기고 해석하지 않는다
    private static Map<String, Object> linkedPayments(LinkedPaymentsToolResponse linked) {
        return Map.of(
                "lookbackDays", linked.lookbackDays(),
                "card", linkedMatch(linked.card()),
                "device", linkedMatch(linked.device()),
                "ip", linkedMatch(linked.ip()));
    }

    private static Map<String, Object> linkedMatch(LinkedPaymentsToolResponse.Match match) {
        return Map.of(
                "provided", match.provided(),
                "otherCustomerCount", match.otherCustomerCount(),
                "paymentCount", match.paymentCount(),
                "resultCounts",
                        match.resultCounts().stream()
                                .map(c -> Map.<String, Object>of("result", c.result(), "count", c.count()))
                                .toList(),
                "declinedCount", match.declinedCount());
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
    private static Map<String, Object> fixedTrace(
            int paymentCount,
            SimilarCasesToolResponse similar,
            PaymentHistoryToolResponse history,
            LinkedPaymentsToolResponse linked) {
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
                        traceStep(
                                "TOOL",
                                "고객 최근 결제 조회",
                                "같은 고객의 최근 " + PaymentHistoryFinder.LOOKBACK_DAYS + "일 결제 (이 사건 제외)",
                                history == null
                                        ? "조회 실패"
                                        : "결제 " + history.payments().size() + "건"),
                        traceStep(
                                "TOOL",
                                "같은 카드 · 기기 · IP 결제 조회",
                                "같은 가맹점 최근 " + LinkedPaymentFinder.LOOKBACK_DAYS + "일, 다른 고객 결제",
                                linked == null ? "조회 실패" : linkedSummary(linked)),
                        traceStep("ASSEMBLE", "고정 문장 보고서 조립", null, "AI 서버가 꺼져 정해진 문장으로 조립")));
        return trace;
    }

    // "카드 결제 3건 · 기기 없음 · IP 정보 없음" — ai-server trace 요약과 같은 말
    private static String linkedSummary(LinkedPaymentsToolResponse linked) {
        return "카드 " + matchSummary(linked.card()) + " · 기기 " + matchSummary(linked.device()) + " · IP "
                + matchSummary(linked.ip());
    }

    private static String matchSummary(LinkedPaymentsToolResponse.Match match) {
        if (!match.provided()) {
            return "정보 없음";
        }
        return match.paymentCount() == 0 ? "없음" : "결제 " + match.paymentCount() + "건";
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
        // AI 보고서와 같은 기준 · 실제 문장 (#326). 정리할 수 없으면 null
        Optional<Criterion> criterion =
                RuleCriterionFormatter.format(rule.code(), rule.thresholdSnapshot(), rule.observedValue());
        detail.put("criterion", criterion.map(Criterion::criterion).orElse(null));
        detail.put("observed", criterion.map(Criterion::observed).orElse(null));
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
