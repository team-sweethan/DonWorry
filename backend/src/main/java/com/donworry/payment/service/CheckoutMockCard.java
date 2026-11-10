package com.donworry.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// 돈워리 결제창의 시연용 가상 카드 (실제 카드사 연동 전). ADR-0064 2-1절
// 카드번호 원문은 없다. FDS에는 고객별 카드 토큰과 마스킹 표시값만 넘긴다.
// 이름은 FDS 결과가 아니라 카드 자체를 나타낸다. 같은 고객이 처음 쓰는 카드면 신규 카드가 된다.
// 한도 초과(카드사 거절) · 해외 발급 카드는 모의 승인 · FDS 입력에 반영되기 전까지 두지 않는다 (#582)
public enum CheckoutMockCard {
    // 결제창이 처음 보여 주는 카드. 같은 고객이면 늘 같은 토큰이라 한 번 쓰면 "쓰던 카드"가 된다
    A("한빛카드", "4821", "KR", "#172554", false),
    // 결제창 [카드 추가]로 구매자가 카드사에서 새로 등록했다고 가정하는 카드 (#766).
    // 결제 시도(세션)마다 토큰 · 끝 4자리가 달라 그 고객이 처음 쓰는 카드가 된다. 같은 시도를 다시 하면 같은 값이다
    N("누리카드", null, "KR", "#2563EB", true);

    // 추가 카드 끝 4자리가 기본 카드나 예전 누리카드(#582의 B)와 같으면 같은 카드로 보이므로 피한다
    private static final Set<String> RESERVED_LAST4 = Set.of("4821", "1173");

    private final String label;
    private final String fixedLast4;
    private final String issuerCountry;
    private final String color;
    private final boolean added;

    CheckoutMockCard(String label, String fixedLast4, String issuerCountry, String color, boolean added) {
        this.label = label;
        this.fixedLast4 = fixedLast4;
        this.issuerCountry = issuerCountry;
        this.color = color;
        this.added = added;
    }

    public static Optional<CheckoutMockCard> from(String code) {
        for (var card : values()) if (card.name().equals(code)) return Optional.of(card);
        return Optional.empty();
    }

    public String label() {
        return label;
    }

    public String issuerCountry() {
        return issuerCountry;
    }

    public String color() {
        return color;
    }

    // 결제창 [카드 추가]로만 나오는 카드인지. 아니면 처음부터 목록에 보인다
    public boolean added() {
        return added;
    }

    // 결제창 · FDS에 보이는 마스킹 번호. 추가 카드는 그 결제 시도의 값이다
    public String masked(String internalCustomerRef, UUID attemptKey) {
        return "**** " + (added ? addedLast4(internalCustomerRef, attemptKey) : fixedLast4);
    }

    // FDS 카드 표시값(경보 상세 · 차단 목록에 보이는 값). 50자 이하. 결제창에 보인 값과 같다
    public String displayLabel(String internalCustomerRef, UUID attemptKey) {
        return label + " " + masked(internalCustomerRef, attemptKey);
    }

    // 가맹점 고객별 카드 토큰. 기본 카드는 같은 고객이면 늘 같은 값이라 "쓰던 카드"가 된다(#582 이전 값 그대로).
    // 추가 카드는 결제 시도마다 달라 늘 그 고객이 처음 쓰는 카드다
    public String token(String internalCustomerRef, UUID attemptKey) {
        return "dwmock_" + HexFormat.of().formatHex(digest(internalCustomerRef, attemptKey), 0, 16);
    }

    // 토큰과 같은 해시의 뒷부분으로 끝 4자리를 만든다. 같은 시도면 같은 값이라 화면과 FDS 표시값이 같다
    private String addedLast4(String internalCustomerRef, UUID attemptKey) {
        byte[] hash = digest(internalCustomerRef, attemptKey);
        int value = ((hash[16] & 0xff) << 8 | (hash[17] & 0xff)) % 10_000;
        String last4 = "%04d".formatted(value);
        while (RESERVED_LAST4.contains(last4)) {
            value = (value + 1) % 10_000;
            last4 = "%04d".formatted(value);
        }
        return last4;
    }

    private byte[] digest(String internalCustomerRef, UUID attemptKey) {
        String seed = "donworry-mock-card:" + internalCustomerRef + ":" + name();
        if (added) seed += ":" + Objects.requireNonNull(attemptKey, "attemptKey");
        try {
            return MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
