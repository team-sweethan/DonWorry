package com.donworry.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

// 돈워리 결제창의 시연용 가상 카드 (실제 카드사 연동 전). ADR-0064 2절
// 카드번호 원문은 없다. FDS에는 고객별 카드 토큰과 마스킹 표시값만 넘긴다.
// 이름은 FDS 결과가 아니라 카드 자체를 나타낸다. 같은 고객이 처음 쓰는 카드면 신규 카드가 된다.
// 한도 초과(카드사 거절) · 해외 발급 카드는 모의 승인 · FDS 입력에 반영되기 전까지 두지 않는다 (#582)
public enum CheckoutMockCard {
    A("한빛카드", "**** 4821", "KR", "#172554"),
    B("누리카드", "**** 1173", "KR", "#2563EB");

    private final String label;
    private final String masked;
    private final String issuerCountry;
    private final String color;

    CheckoutMockCard(String label, String masked, String issuerCountry, String color) {
        this.label = label;
        this.masked = masked;
        this.issuerCountry = issuerCountry;
        this.color = color;
    }

    public static Optional<CheckoutMockCard> from(String code) {
        for (var card : values()) if (card.name().equals(code)) return Optional.of(card);
        return Optional.empty();
    }

    public String label() {
        return label;
    }

    public String masked() {
        return masked;
    }

    public String issuerCountry() {
        return issuerCountry;
    }

    public String color() {
        return color;
    }

    // FDS 카드 표시값(경보 상세 · 차단 목록에 보이는 값). 50자 이하.
    public String displayLabel() {
        return label + " " + masked;
    }

    // 가맹점 고객별로 고정된 카드 토큰. 같은 고객이 같은 카드를 쓰면 같은 값이라 "쓰던 카드"가 된다.
    public String token(String internalCustomerRef) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("donworry-mock-card:" + internalCustomerRef + ":" + name())
                            .getBytes(StandardCharsets.UTF_8));
            return "dwmock_" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
