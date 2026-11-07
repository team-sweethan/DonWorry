package com.donworry.fds.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// 보안 직원 화면 · 다른 모듈로 내보내는 구매자 이메일 가리기 (#603, ISMS-P 2.6.3 화면별 같은 가림 기준)
// P5 PaymentSignalMasking.email과 같은 규칙이다. 모듈 경계 때문에 가져다 쓰지 못해 복사했다 — 바꾸면 둘 다 바꾼다
final class FdsIdentifierMasking {
    static final String HIDDEN = "***";
    static final String RAW_EMAIL_KEY = "buyerEmail";
    static final String MASKED_EMAIL_KEY = "maskedBuyerEmail";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private FdsIdentifierMasking() {}

    // chk990727@gmail.com → ch***@gmail.com / a@b.com → a***@b.com
    static String email(String value) {
        if (value == null) return null;
        int at = value.lastIndexOf('@');
        if (at <= 0 || at == value.length() - 1) return HIDDEN;
        var local = value.substring(0, at);
        return prefix(local, local.codePointCount(0, local.length()) > 2 ? 2 : 1) + HIDDEN + value.substring(at);
    }

    // #603 전에 저장한 R07 근거에는 이메일 원문(buyerEmail)이 있다. 내보낼 때 가린 값(maskedBuyerEmail)으로 바꾼다
    // 가린 값을 다시 가리면 짧은 이메일은 모양이 바뀌므로(a***@ → a****@) 키 이름으로 원문과 가린 값을 구분한다
    static String ruleEvidence(String observedJson) {
        if (observedJson == null || !observedJson.contains("\"" + RAW_EMAIL_KEY + "\"")) return observedJson;
        try {
            if (!(MAPPER.readTree(observedJson) instanceof ObjectNode node)) return null;
            var raw = node.remove(RAW_EMAIL_KEY);
            node.put(MASKED_EMAIL_KEY, raw != null && raw.isString() ? email(raw.stringValue()) : null);
            return MAPPER.writeValueAsString(node);
        } catch (JacksonException e) {
            // 읽지 못하면 원문이 섞였을 수 있어 근거를 통째로 내보내지 않는다
            return null;
        }
    }

    private static String prefix(String value, int codePoints) {
        return value.substring(0, value.offsetByCodePoints(0, codePoints));
    }
}
