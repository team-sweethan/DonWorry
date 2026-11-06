package com.donworry.investigation.report.service;

import com.donworry.investigation.report.dto.CasePaymentsToolResponse;
import com.donworry.investigation.report.dto.DecisionBasisToolResponse;
import com.donworry.investigation.report.dto.DecisionBasisToolResponse.Basis;
import com.donworry.investigation.report.dto.DecisionBasisToolResponse.Normal;
import com.donworry.investigation.report.dto.DecisionBasisToolResponse.PaymentBasis;
import com.donworry.investigation.report.dto.DecisionBasisToolResponse.Signal;
import com.donworry.investigation.report.dto.FdsEvidenceToolResponse;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse;
import com.donworry.investigation.report.dto.LinkedPaymentsToolResponse.Match;
import com.donworry.investigation.report.dto.PaymentHistoryToolResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

// 판정 근거 · 판정 기준표 (ADR-0009 결정 7) — 권고는 AI가 아니라 이 코드가 정한다
// #501 ~ #614까지 ai-server evidence.py에 있던 계산을 그대로 옮겼다 (#618). AI 서버가 꺼져도 고정 보고서가 같은 기준표를 쓴다
// 징후를 고위험 · 주의로 나누고, 정상 거래 근거가 설명하는 징후에만 정상 사유를 붙인다
// 같은 입력이면 언제나 같은 결과다 — 조회 · 저장은 하지 않는다
public final class DecisionBasisCalculator {

    public static final String HIGH_RISK = "HIGH_RISK";
    public static final String CAUTION = "CAUTION";

    public static final String HIGH_RISK_WITH_OTHERS = "HIGH_RISK_WITH_OTHERS";
    public static final String HIGH_RISK_ONLY = "HIGH_RISK_ONLY";
    public static final String CAUTION_ONLY = "CAUTION_ONLY";
    // 정상 사유가 없는 징후가 남지 않음 — 징후가 0개인 결제도 이 칸이다 (#618 문구 정리)
    public static final String ALL_EXPLAINED = "ALL_EXPLAINED";

    public static final String REJECT_REVIEW = "REJECT_REVIEW";
    public static final String NEEDS_CHECK = "NEEDS_CHECK";
    public static final String ACCEPT_REVIEW = "ACCEPT_REVIEW";

    // 칸 → 권고. 권고 값 3가지는 화면 · P3 판정 패널과의 약속이라 바꾸지 않는다
    public static final Map<String, String> RECOMMENDATION_BY_RULE = Map.of(
            HIGH_RISK_WITH_OTHERS, REJECT_REVIEW,
            HIGH_RISK_ONLY, NEEDS_CHECK,
            CAUTION_ONLY, NEEDS_CHECK,
            ALL_EXPLAINED, ACCEPT_REVIEW);

    static final String OVERSEAS = "OVERSEAS";
    static final String LINKED_CARD = "LINKED_CARD";
    static final String LINKED_DEVICE = "LINKED_DEVICE";
    static final String LINKED_IP = "LINKED_IP";
    // 점수 없음(평가 오류 · 평가 없음) — "징후 없음"으로 읽지 않게 주의 징후로 둔다 (#532)
    static final String FDS_UNCONFIRMED = "FDS_UNCONFIRMED";
    static final String FDS_UNCONFIRMED_LABEL = "FDS 평가 결과 미확인";
    static final String SIMILAR_AMOUNT = "SIMILAR_AMOUNT";
    // 결제별 칸이 없을 때(예전 응답) 붙이는 징후 이름 앞말 — 어느 결제의 값인지 모르니 사실대로 쓴다 (#533)
    static final String CASE_LEVEL_PREFIX = "이 사건 결제 중 ";

    private record RuleGrade(String label, String level) {}

    // 규칙 코드 → (징후 이름, 등급). 징후 이름은 FDS 규칙 이름 그대로 — Case 상세 적중 규칙 · 조사 요약 밑줄과 맞춘다 (#505)
    // 모르는 규칙 코드는 그 규칙 이름으로 주의 징후가 된다
    private static final Map<String, RuleGrade> RULES = Map.of(
            "R01", new RuleGrade("고액 결제", CAUTION),
            "R02", new RuleGrade("신규 단말기", CAUTION),
            "R03", new RuleGrade("신규 카드", CAUTION),
            "R04", new RuleGrade("단시간 반복 결제", CAUTION),
            "R05", new RuleGrade("고위험 국가 IP", CAUTION),
            "R06", new RuleGrade("카드 테스팅", HIGH_RISK),
            "R07", new RuleGrade("차단 목록", HIGH_RISK),
            "R08", new RuleGrade("거절 후 재시도", HIGH_RISK));
    // 예전 영문 코드도 같은 규칙으로 읽는다
    private static final Map<String, String> ALIASES = Map.of(
            "HIGH_AMOUNT", "R01",
            "NEW_DEVICE", "R02",
            "NEW_CARD", "R03",
            "RAPID_REPEAT_PAYMENT", "R04");

    // 판정 전에 확인할 것 (#591) — 징후마다 직원이 할 일을 코드가 정한다 (AI가 고르지 않는다)
    // 새 징후를 추가하면 여기도 한 줄 (DecisionBasisCalculatorTests가 검사)
    static final Map<String, String> CHECK_BEFORE = Map.ofEntries(
            Map.entry("R01", "고객의 평소 결제 금액과 비교 · 단체 주문 같은 구매 목적 확인"),
            Map.entry("R02", "고객이 최근 기기를 바꿨는지 가맹점에 확인"),
            Map.entry("R03", "새로 추가한 본인 카드인지 카드사에 승인 내역 확인"),
            Map.entry("R04", "한 주문을 나눠 낸 결제인지 주문 내역 확인"),
            Map.entry("R05", "해외 체류 · VPN 사용 등 접속 경위 확인"),
            Map.entry("R06", "소액 결제가 여러 카드로 이어졌는지 카드사에 확인"),
            Map.entry("R07", "차단 목록에 오른 사유 · 등록 시점 확인"),
            Map.entry("R08", "카드사에 거절 사유 확인"),
            Map.entry(OVERSEAS, "해외 체류 · VPN 사용 등 접속 경위 확인"),
            Map.entry(LINKED_CARD, "가족처럼 카드를 함께 쓰는 사이인지 확인"),
            Map.entry(LINKED_DEVICE, "매장 키오스크 같은 공용 기기인지 가맹점에 확인"),
            Map.entry(LINKED_IP, "매장 · 회사 같은 공용 와이파이인지 가맹점에 문의"),
            Map.entry(FDS_UNCONFIRMED, "FDS 평가가 왜 없었는지 평가 기록 확인"));

    // 비슷한 금액: 이번 금액의 0.5 ~ 1.5배, 매입 완료 2건 이상
    static final double SIMILAR_LOW = 0.5;
    static final double SIMILAR_HIGH = 1.5;
    static final int SIMILAR_MIN_COUNT = 2;
    // 사건 결제 앞뒤 10분 안의 결제는 평소 결제가 아니다 (단시간 반복 규칙 10분 안 3회와 같은 기준)
    static final Duration BURST_WINDOW = Duration.ofMinutes(10);

    // 정상 거래 근거가 설명할 수 있는 징후 — 정해진 징후만 설명한다 (금액 이력이 새 카드 · 해외 접속을 덮지 않게)
    // "전에 승인된 본인 카드"는 쓰지 않는다 — R03이 꺼진 경우와 구분할 수 없고 고객 이력은 카드별이 아니다 (#501 리뷰)
    private static final Map<String, List<String>> EXPLAINS = Map.of(SIMILAR_AMOUNT, List.of("R01"));

    // 같은 행동을 두 번 세지 않는다 — 거절 후 재시도 · 카드 테스팅은 이미 단시간 반복 결제다
    private static final Map<String, List<String>> COUNTED_IN = Map.of("R04", List.of("R08", "R06"));

    private DecisionBasisCalculator() {}

    // 사건 결제마다 기준표를 계산한다 — 금액 · 요청 시각을 모르거나 FDS 근거가 없는 결제는 뺀다 (받는 쪽이 추가 확인으로 둔다)
    // history(30일 기본 조회)가 null이면 조회 실패 — 정상 사유 없이 계산한다. linked가 null이면 기준표를 주지 않는다 (아래)
    public static DecisionBasisToolResponse forCase(
            CasePaymentsToolResponse cases,
            FdsEvidenceToolResponse fds,
            PaymentHistoryToolResponse history,
            LinkedPaymentsToolResponse linked,
            Instant retrievedAt) {
        // 연결 결제 조회 실패(null)는 "연결 징후 없음"이 아니다 — 고위험 징후(같은 카드 · 기기)를 확인하지 못했으니 기준표를 주지 않는다
        // 빈 목록이면 ai-server가 결제마다 추가 확인 + 한계 줄로 둔다 (#618 Codex 리뷰)
        // 고객 이력 실패(null)는 정상 사유만 빠져 권고가 매입 진행 쪽으로 올라가지 않으므로 그대로 계산한다
        if (linked == null) {
            return DecisionBasisToolResponse.builder()
                    .caseId(cases.caseId())
                    .evidenceVersion(cases.evidenceVersion())
                    .source("P4")
                    .retrievedAt(retrievedAt)
                    .payments(List.of())
                    .build();
        }
        Map<String, FdsEvidenceToolResponse.Payment> evidence = fds.payments().stream()
                .collect(
                        Collectors.toMap(FdsEvidenceToolResponse.Payment::paymentId, Function.identity(), (a, b) -> b));
        List<PaymentBasis> payments = cases.payments().stream()
                .filter(p -> p.amount() != null && p.requestedAt() != null && evidence.containsKey(p.paymentId()))
                .map(p -> new PaymentBasis(
                        p.paymentId(), basis(p, evidence.get(p.paymentId()), cases.payments(), history, linked)))
                .toList();
        return DecisionBasisToolResponse.builder()
                .caseId(cases.caseId())
                .evidenceVersion(cases.evidenceVersion())
                .source("P4")
                .retrievedAt(retrievedAt)
                .payments(payments)
                .build();
    }

    public static Basis basis(
            CasePaymentsToolResponse.Payment payment,
            FdsEvidenceToolResponse.Payment evidence,
            List<CasePaymentsToolResponse.Payment> casePayments,
            PaymentHistoryToolResponse history,
            LinkedPaymentsToolResponse linked) {
        List<RawSignal> raw = signals(evidence, linked);
        List<RawNormal> normals = normals(payment, casePayments, history);
        Map<String, String> reasonFor = new LinkedHashMap<>();
        for (RawNormal normal : normals) {
            for (String target : EXPLAINS.get(normal.key())) {
                reasonFor.putIfAbsent(target, normal.label());
            }
        }
        Map<String, String> labels = new LinkedHashMap<>();
        raw.forEach(s -> labels.put(s.key(), s.label()));
        List<Signal> signals = raw.stream()
                .map(s -> Signal.builder()
                        .key(s.key())
                        .label(s.label())
                        .detail(s.detail())
                        .level(s.level())
                        // 고위험 징후는 자료만으로 정상 사유를 붙이지 않는다 — 직원 확인으로만 풀린다
                        .normalReason(CAUTION.equals(s.level()) ? reasonFor.get(s.key()) : null)
                        .countedIn(COUNTED_IN.getOrDefault(s.key(), List.of()).stream()
                                .filter(labels::containsKey)
                                .map(labels::get)
                                .findFirst()
                                .orElse(null))
                        .checkBefore(CHECK_BEFORE.get(s.key()))
                        .build())
                .toList();
        Set<String> present = labels.keySet();
        String rule = rule(signals);
        return Basis.builder()
                .signals(signals)
                .normals(normals.stream()
                        .map(n -> Normal.builder()
                                .key(n.key())
                                .label(n.label())
                                .canExplain(EXPLAINS.get(n.key()).stream()
                                        .map(DecisionBasisCalculator::labelOf)
                                        .toList())
                                .explains(EXPLAINS.get(n.key()).stream()
                                        .filter(present::contains)
                                        .map(DecisionBasisCalculator::labelOf)
                                        .toList())
                                .build())
                        .toList())
                .rule(rule)
                .recommendation(RECOMMENDATION_BY_RULE.get(rule))
                .build();
    }

    // 종합 권고 (ADR-0009 결정 4) — 하나라도 승인 취소 검토면 승인 취소 검토, 모두 매입 진행 검토면 매입 진행 검토, 나머지는 추가 확인
    public static String overall(Collection<String> recommendations) {
        if (recommendations.contains(REJECT_REVIEW)) {
            return REJECT_REVIEW;
        }
        if (!recommendations.isEmpty() && recommendations.stream().allMatch(ACCEPT_REVIEW::equals)) {
            return ACCEPT_REVIEW;
        }
        return NEEDS_CHECK;
    }

    private record RawSignal(String key, String label, String detail, String level) {}

    private record RawNormal(String key, String label) {}

    private static List<RawSignal> signals(
            FdsEvidenceToolResponse.Payment evidence, LinkedPaymentsToolResponse linked) {
        List<RawSignal> found = new ArrayList<>(evidence.ruleHits().stream()
                .map(DecisionBasisCalculator::ruleSignal)
                .toList());
        if (evidence.riskScore() == null || "ERROR".equals(evidence.fdsResult())) {
            found.add(new RawSignal(FDS_UNCONFIRMED, FDS_UNCONFIRMED_LABEL, null, CAUTION));
        }
        Set<String> keys = found.stream().map(RawSignal::key).collect(Collectors.toSet());
        String country = evidence.signals() == null ? null : evidence.signals().ipCountryCode();
        // 고위험 국가 규칙이 걸렸으면 해외 접속을 따로 세지 않는다
        if (country != null && !country.isEmpty() && !"KR".equals(country) && !keys.contains("R05")) {
            found.add(new RawSignal(OVERSEAS, "해외 접속 (" + country + ")", null, CAUTION));
        }
        // 같은 카드 · 기기 · IP 결제는 그 결제의 것만 붙인다 (#533) — 결제별 칸이 없으면 사건 단위로 붙이되 사실대로 쓴다
        if (linked != null) {
            LinkedPaymentsToolResponse.PaymentLinks own = linked.payments() == null
                    ? null
                    : linked.payments().stream()
                            .filter(l -> Objects.equals(l.paymentId(), evidence.paymentId()))
                            .findFirst()
                            .orElse(null);
            String prefix = own == null ? CASE_LEVEL_PREFIX : "";
            Match card = own == null ? linked.card() : own.card();
            Match device = own == null ? linked.device() : own.device();
            Match ip = own == null ? linked.ip() : own.ip();
            addLinked(found, LINKED_CARD, prefix + "같은 카드를 다른 고객이 사용", HIGH_RISK, card);
            addLinked(found, LINKED_DEVICE, prefix + "같은 기기를 다른 고객이 사용", HIGH_RISK, device);
            // 같은 IP는 공유기 · 회사 망에서도 흔해 주의 징후로 둔다
            addLinked(found, LINKED_IP, prefix + "같은 IP를 다른 고객이 사용", CAUTION, ip);
        }
        return found;
    }

    private static RawSignal ruleSignal(FdsEvidenceToolResponse.RuleHit rule) {
        String key = ALIASES.getOrDefault(rule.code(), rule.code());
        RuleGrade grade = RULES.getOrDefault(key, new RuleGrade(rule.name(), CAUTION));
        String detail = rule.criterion() != null
                        && !rule.criterion().isEmpty()
                        && rule.observed() != null
                        && !rule.observed().isEmpty()
                ? rule.criterion() + " · 이번 " + rule.observed()
                : null;
        return new RawSignal(key, grade.label(), detail, grade.level());
    }

    private static void addLinked(List<RawSignal> found, String key, String label, String level, Match match) {
        if (match == null || !match.provided() || match.paymentCount() == 0) {
            return;
        }
        String people = match.otherCustomerCount() == 1 ? "1명" : "여러 명";
        String declined = match.declinedCount() > 0 ? " · 그 결제 중 카드사 거절 있음" : "";
        found.add(new RawSignal(key, label, "최근 30일 다른 고객 " + people + declined, level));
    }

    // 평소 결제 = 사건 전 결제. 사건 결제 앞뒤 10분 안(같은 연속 결제)과 첫 사건 결제 뒤 결제는 정상 근거가 아니다
    // 사건은 30분 안의 보류 결제를 계속 합쳐 길어진다 — 마지막 사건 결제를 기준으로 하면 사건 도중 결제가 섞였다 (#532)
    private static List<PaymentHistoryToolResponse.Payment> usualHistory(
            List<CasePaymentsToolResponse.Payment> casePayments, PaymentHistoryToolResponse history) {
        List<Instant> caseTimes = casePayments.stream()
                .map(CasePaymentsToolResponse.Payment::requestedAt)
                .filter(Objects::nonNull)
                .toList();
        Instant first = caseTimes.stream().min(Instant::compareTo).orElse(null);
        List<PaymentHistoryToolResponse.Payment> usual = new ArrayList<>();
        for (PaymentHistoryToolResponse.Payment payment : history.payments()) {
            Instant at = payment.requestedAt();
            if (at != null
                    && caseTimes.stream()
                            .anyMatch(t -> Duration.between(t, at).abs().compareTo(BURST_WINDOW) <= 0)) {
                continue;
            }
            if (at != null && first != null && at.isAfter(first)) {
                continue;
            }
            usual.add(payment);
        }
        return usual;
    }

    private static List<RawNormal> normals(
            CasePaymentsToolResponse.Payment payment,
            List<CasePaymentsToolResponse.Payment> casePayments,
            PaymentHistoryToolResponse history) {
        if (history == null) {
            return List.of();
        }
        long amount = payment.amount() == null ? 0 : payment.amount();
        long similar = usualHistory(casePayments, history).stream()
                .filter(p -> "CAPTURED".equals(p.paymentStatus())
                        && p.amount() != null
                        && amount * SIMILAR_LOW <= p.amount()
                        && p.amount() <= amount * SIMILAR_HIGH)
                .count();
        if (amount != 0 && similar >= SIMILAR_MIN_COUNT) {
            return List.of(new RawNormal(SIMILAR_AMOUNT, "최근 30일 비슷한 금액의 결제 " + similar + "건 매입 완료"));
        }
        return List.of();
    }

    private static String labelOf(String key) {
        RuleGrade grade = RULES.get(key);
        return grade == null ? key : grade.label();
    }

    private static String rule(List<Signal> signals) {
        List<Signal> open = signals.stream()
                .filter(s -> s.normalReason() == null && s.countedIn() == null)
                .toList();
        if (open.stream().anyMatch(s -> HIGH_RISK.equals(s.level()))) {
            // 점수 없음은 판정을 올리지 않는다 — 고위험과 겹치는 "다른 징후"로 세지 않는다 (#532)
            long others =
                    open.stream().filter(s -> !FDS_UNCONFIRMED.equals(s.key())).count();
            return others >= 2 ? HIGH_RISK_WITH_OTHERS : HIGH_RISK_ONLY;
        }
        return open.isEmpty() ? ALL_EXPLAINED : CAUTION_ONLY;
    }
}
