package com.donworry.payment.entity;

import java.util.Locale;
import java.util.regex.Pattern;
import lombok.Builder;

// 가맹점이 결제 시도마다 보내는 FDS 식별자다. 모두 선택값이며 카드 원문은 받지 않는다 (#305 · #325).
// 길이와 정규화는 P2 FdsEvaluationRequest와 같게 맞춰, 저장한 값이 평가 요청에서 거절되지 않게 한다.
@Builder(toBuilder = true)
public record PaymentRiskSignals(
        String deviceId,
        String cardToken,
        String cardDisplayLabel,
        String ipCountryCode,
        String ipAddress,
        String buyerEmail) {
    private static final PaymentRiskSignals NONE = new PaymentRiskSignals(null, null, null, null, null, null);
    // 구분자(공백 · 하이픈)를 끼워도 13자리 이상 이어지는 숫자는 마스킹하지 않은 카드번호로 본다.
    private static final Pattern CARD_NUMBER = Pattern.compile("\\d(?:[ -]?\\d){12,}");
    private static final Pattern IPV4 =
            Pattern.compile("((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9A-Fa-f:.]+");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+");

    public PaymentRiskSignals {
        deviceId = optional(deviceId, 100);
        cardToken = optional(cardToken, 255);
        if (cardToken != null && CARD_NUMBER.matcher(cardToken).matches())
            throw new IllegalArgumentException("카드 토큰 자리에 카드번호를 받지 않습니다");
        cardDisplayLabel = optional(cardDisplayLabel, 50);
        if (cardDisplayLabel != null && CARD_NUMBER.matcher(cardDisplayLabel).find())
            throw new IllegalArgumentException("카드 표시값은 마스킹해야 합니다");
        ipCountryCode = optional(ipCountryCode, 2);
        if (ipCountryCode != null) {
            if (!ipCountryCode.matches("[A-Za-z]{2}")) throw new IllegalArgumentException("국가 코드는 영문 2자입니다");
            ipCountryCode = ipCountryCode.toUpperCase(Locale.ROOT);
        }
        ipAddress = optional(ipAddress, 45);
        if (ipAddress != null && !ipAddress(ipAddress)) throw new IllegalArgumentException("IP 주소 형식이 아닙니다");
        buyerEmail = optional(buyerEmail, 254);
        if (buyerEmail != null) {
            if (!EMAIL.matcher(buyerEmail).matches()) throw new IllegalArgumentException("이메일 형식이 아닙니다");
            buyerEmail = buyerEmail.toLowerCase(Locale.ROOT);
        }
    }

    public static PaymentRiskSignals none() {
        return NONE;
    }

    public static PaymentRiskSignals orNone(PaymentRiskSignals signals) {
        return signals == null ? NONE : signals;
    }

    private static String optional(String value, int maxLength) {
        if (value == null) return null;
        if (value.isBlank() || !value.equals(value.strip()) || value.codePointCount(0, value.length()) > maxLength)
            throw new IllegalArgumentException("식별자는 양끝 공백 없이 1~" + maxLength + "자여야 합니다");
        return value;
    }

    // 이름 조회(DNS)를 하지 않도록 문자 형식을 먼저 거른 뒤 IPv6만 리터럴로 해석한다.
    private static boolean ipAddress(String value) {
        if (IPV4.matcher(value).matches()) return true;
        if (!value.contains(":") || !IPV6_CHARS.matcher(value).matches()) return false;
        try {
            java.net.InetAddress.getByName("[" + value + "]");
            return true;
        } catch (java.net.UnknownHostException exception) {
            return false;
        }
    }
}
