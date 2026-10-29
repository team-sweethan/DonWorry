package com.donworry.investigation.report.service;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// FDS 기준값 · 실제값 JSON에서 숫자만 골라 "50만 원 이상" / "60만 원" 문장으로 만든다 (#326)
// 허용한 규칙의 숫자 키만 읽는다 — 식별자가 든 R05 · R07, 모르는 규칙, 값이 빠진 JSON은 비운다
final class RuleCriterionFormatter {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    // criterion은 기준, observed는 이번 결제에서 잰 값
    record Criterion(String criterion, String observed) {}

    private RuleCriterionFormatter() {}

    static Optional<Criterion> format(String ruleCode, String thresholdJson, String observedJson) {
        if (ruleCode == null || thresholdJson == null || observedJson == null) {
            return Optional.empty();
        }
        JsonNode threshold;
        JsonNode observed;
        try {
            threshold = MAPPER.readTree(thresholdJson);
            observed = MAPPER.readTree(observedJson);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(criterionOf(ruleCode, threshold, observed));
        } catch (MissingValueException e) {
            return Optional.empty();
        }
    }

    private static Criterion criterionOf(String ruleCode, JsonNode t, JsonNode o) {
        return switch (ruleCode) {
            case "R01" -> new Criterion(won(num(t, "amountGte")) + " 이상", won(num(o, "amount")));
            case "R02" -> priorApproved("단말기", o);
            case "R03" -> priorApproved("카드", o);
            case "R04" ->
                new Criterion(
                        num(t, "windowMinutes") + "분 안 " + num(t, "attemptCountGte") + "회 이상",
                        num(o, "attemptCount") + "회");
            case "R06" ->
                new Criterion(
                        num(t, "windowMinutes") + "분 안 " + won(num(t, "smallAmountLte")) + " 이하 결제 "
                                + num(t, "attemptCountGte") + "회 · 카드 " + num(t, "cardCountGte") + "장 · 거절 "
                                + num(t, "declinedCountGte") + "회 이상",
                        won(num(t, "smallAmountLte")) + " 이하 결제 " + num(o, "smallAttemptCount") + "회 · 카드 "
                                + num(o, "cardCount") + "장 · 거절 " + num(o, "declinedCount") + "회");
            case "R08" ->
                new Criterion(
                        num(t, "windowMinutes") + "분 안 거절 " + num(t, "declinedCountGte") + "회 이상",
                        "거절 " + num(o, "declinedCount") + "회");
            default -> null;
        };
    }

    // R02 · R03은 이 고객의 이전 승인 결제가 있는데 같은 카드 · 단말기가 없을 때 걸린다
    private static Criterion priorApproved(String target, JsonNode o) {
        return new Criterion(
                "이전 승인 결제에 없던 " + target, "이전 승인 " + num(o, "approvedHistoryCount") + "건 중 같은 " + target + " 없음");
    }

    private static long num(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber()) {
            throw new MissingValueException();
        }
        return value.longValue();
    }

    // 만 원 단위로 떨어지면 "50만 원", 아니면 "12,345원"
    static String won(long amount) {
        NumberFormat grouping = NumberFormat.getIntegerInstance(Locale.KOREA);
        if (amount != 0 && amount % 10_000 == 0) {
            return grouping.format(amount / 10_000) + "만 원";
        }
        return grouping.format(amount) + "원";
    }

    private static final class MissingValueException extends RuntimeException {
        MissingValueException() {
            super(null, null, false, false);
        }
    }
}
