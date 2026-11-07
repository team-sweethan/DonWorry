package com.donworry.fds.service;

import java.util.List;
import java.util.function.UnaryOperator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// 보안 직원 화면 · 다른 모듈로 내보내는 구매자 식별값 가리기 (#603 이메일, #628 IP · 기기 ID · 카드 토큰)
// ISMS-P 2.6.3: 같은 항목은 화면마다 같은 기준으로 가린다
// P5 PaymentSignalMasking과 같은 규칙이다. 모듈 경계 때문에 가져다 쓰지 못해 복사했다 — 바꾸면 둘 다 바꾼다
final class FdsIdentifierMasking {
    static final String HIDDEN = "***";
    static final String RAW_EMAIL_KEY = "buyerEmail";
    static final String MASKED_EMAIL_KEY = "maskedBuyerEmail";
    static final String RAW_DEVICE_KEY = "deviceId";
    static final String MASKED_DEVICE_KEY = "maskedDeviceId";
    static final String RAW_IP_KEY = "ipAddress";
    static final String MASKED_IP_KEY = "maskedIpAddress";
    // 카드 토큰은 가린 값도 내보내지 않는다. 사람이 볼 값은 표시값(VISA •••• 1234)이다
    static final String RAW_CARD_TOKEN_KEY = "cardToken";
    static final String CARD_DISPLAY_LABEL_KEY = "cardDisplayLabel";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final List<MaskedKey> MASKED_KEYS = List.of(
            new MaskedKey(RAW_EMAIL_KEY, MASKED_EMAIL_KEY, FdsIdentifierMasking::email),
            new MaskedKey(RAW_DEVICE_KEY, MASKED_DEVICE_KEY, FdsIdentifierMasking::deviceId),
            new MaskedKey(RAW_IP_KEY, MASKED_IP_KEY, FdsIdentifierMasking::ipAddress));

    private FdsIdentifierMasking() {}

    // 211.234.10.5 → 211.234.*.* / 2001:db8:85a3::1 → 2001:db8:*
    static String ipAddress(String value) {
        if (value == null) return null;
        if (!value.contains(":")) {
            var parts = value.split("\\.", -1);
            return parts.length == 4 && !parts[0].isEmpty() && !parts[1].isEmpty()
                    ? parts[0] + "." + parts[1] + ".*.*"
                    : HIDDEN;
        }
        var groups = value.split(":", -1);
        var kept = new StringBuilder();
        for (int i = 0; i < Math.min(2, groups.length) && !groups[i].isEmpty(); i++)
            kept.append(groups[i]).append(':');
        return kept.isEmpty() ? HIDDEN : kept + "*";
    }

    // 앞 8자만, 8자 이하면 절반만 — 짧은 ID가 통째로 보이지 않게 한다
    static String deviceId(String value) {
        if (value == null) return null;
        int length = value.codePointCount(0, value.length());
        return prefix(value, length > 8 ? 8 : length / 2) + "…";
    }

    // chk990727@gmail.com → ch***@gmail.com / a@b.com → a***@b.com
    static String email(String value) {
        if (value == null) return null;
        int at = value.lastIndexOf('@');
        if (at <= 0 || at == value.length() - 1) return HIDDEN;
        var local = value.substring(0, at);
        return prefix(local, local.codePointCount(0, local.length()) > 2 ? 2 : 1) + HIDDEN + value.substring(at);
    }

    // 예전에 저장한 근거에는 원문(buyerEmail · deviceId · ipAddress · cardToken)이 있다. 내보낼 때 가린 값으로 바꾼다
    // 가린 값을 다시 가리면 짧은 값은 모양이 바뀌므로(a***@ → a****@) 키 이름으로 원문과 가린 값을 구분한다
    // 카드 토큰은 빼고, 근거에 표시값이 없으면(R03) 그 평가의 표시값을 넣는다
    static String ruleEvidence(String observedJson, String cardDisplayLabel) {
        if (observedJson == null || !hasRawKey(observedJson)) return observedJson;
        try {
            if (!(MAPPER.readTree(observedJson) instanceof ObjectNode node)) return null;
            for (var key : MASKED_KEYS) {
                if (node.has(key.raw())) {
                    var raw = node.remove(key.raw());
                    node.put(key.masked(), raw.isString() ? key.mask().apply(raw.stringValue()) : null);
                }
            }
            if (node.has(RAW_CARD_TOKEN_KEY)) {
                node.remove(RAW_CARD_TOKEN_KEY);
                if (!node.has(CARD_DISPLAY_LABEL_KEY)) node.put(CARD_DISPLAY_LABEL_KEY, cardDisplayLabel);
            }
            return MAPPER.writeValueAsString(node);
        } catch (JacksonException e) {
            // 읽지 못하면 원문이 섞였을 수 있어 근거를 통째로 내보내지 않는다
            return null;
        }
    }

    private static boolean hasRawKey(String json) {
        return json.contains("\"" + RAW_CARD_TOKEN_KEY + "\"")
                || MASKED_KEYS.stream().anyMatch(key -> json.contains("\"" + key.raw() + "\""));
    }

    private static String prefix(String value, int codePoints) {
        return value.substring(0, value.offsetByCodePoints(0, codePoints));
    }

    private record MaskedKey(String raw, String masked, UnaryOperator<String> mask) {}
}
