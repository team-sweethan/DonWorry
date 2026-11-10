package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CheckoutMockCardTests {
    private static final String CUSTOMER = "7:42";

    // 기본 카드 토큰은 #582 때와 같은 값이다. 바뀌면 이미 한빛카드를 쓴 고객도 신규 카드(R03)로 잡힌다 (#766)
    @Test
    void basicCardToken_isSameForEveryAttemptAndUnchanged() throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(("donworry-mock-card:" + CUSTOMER + ":A").getBytes(StandardCharsets.UTF_8));
        String before = "dwmock_" + HexFormat.of().formatHex(hash, 0, 16);

        assertThat(CheckoutMockCard.A.token(CUSTOMER, UUID.randomUUID()))
                .isEqualTo(CheckoutMockCard.A.token(CUSTOMER, null))
                .isEqualTo(before);
        assertThat(CheckoutMockCard.A.displayLabel(CUSTOMER, UUID.randomUUID())).isEqualTo("한빛카드 **** 4821");
    }

    // 추가 카드는 같은 결제 시도면 같은 카드, 다른 시도면 다른 카드다. 끝 4자리는 기본 카드 · 예전 누리카드와 겹치지 않는다
    @Test
    void addedCard_isStablePerAttemptAndNeverLooksLikeOtherCards() {
        var attempt = UUID.randomUUID();
        assertThat(CheckoutMockCard.N.token(CUSTOMER, attempt))
                .isEqualTo(CheckoutMockCard.N.token(CUSTOMER, attempt))
                .isNotEqualTo(CheckoutMockCard.N.token(CUSTOMER, UUID.randomUUID()))
                .isNotEqualTo(CheckoutMockCard.N.token("7:43", attempt));
        assertThat(CheckoutMockCard.N.displayLabel(CUSTOMER, attempt))
                .isEqualTo("누리카드 " + CheckoutMockCard.N.masked(CUSTOMER, attempt));

        for (int i = 0; i < 2_000; i++) {
            assertThat(CheckoutMockCard.N.masked(CUSTOMER, UUID.randomUUID()))
                    .matches("[*]{4} [0-9]{4}")
                    .isNotIn("**** 4821", "**** 1173");
        }
    }
}
