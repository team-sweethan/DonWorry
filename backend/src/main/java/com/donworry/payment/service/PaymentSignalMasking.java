package com.donworry.payment.service;

// 거래 내역 화면에 내보내는 식별자 가리기 (#363). 원문은 이 모듈 밖으로 나가지 않는다
// 모르는 모양이면 일부도 보여 주지 않고 "***"로 막는다 — 예외로 목록 전체가 실패하지 않게 한다
final class PaymentSignalMasking {
    static final String HIDDEN = "***";

    private PaymentSignalMasking() {}

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

    private static String prefix(String value, int codePoints) {
        return value.substring(0, value.offsetByCodePoints(0, codePoints));
    }
}
