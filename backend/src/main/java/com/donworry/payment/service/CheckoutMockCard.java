package com.donworry.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

// 돈워리 결제창의 모의 카드 (실제 카드사 연동 전, 프로토타입 v4.2 SHOP_CARDS: A·B·D·C). ADR-0064 2절
// 카드번호 원문은 없다. FDS에는 고객별 카드 토큰과 마스킹 표시값만 넘긴다.
// 발급국·한도 초과는 아직 화면 안내용이다. 모의 승인과 FDS 입력 반영은 후속.
public enum CheckoutMockCard {
    A("모의 카드 A (쓰던 카드)", "**** 4821", "KR", "#172554"),
    B("모의 카드 B (처음 쓰는 카드)", "**** 1173", "KR", "#2563EB"),
    D("모의 카드 D (해외 발급 · 미국)", "**** 5520", "US", "#0F766E"),
    C("모의 카드 C (한도 초과)", "**** 9034", "KR", "#64748B");

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

    // FDS 카드 표시값. 50자 이하.
    public String displayLabel() {
        return "모의 카드 " + name() + " " + masked;
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
