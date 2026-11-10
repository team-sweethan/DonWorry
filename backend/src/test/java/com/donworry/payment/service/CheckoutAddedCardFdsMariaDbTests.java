package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.payment.dto.CheckoutResponses;
import com.donworry.payment.dto.PaymentSessionCreateRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

// 결제창 [카드 추가] 카드가 실제 P2 평가에서 신규 카드(R03)가 되는지 본다 (#766, ADR-0064 2-1절).
// 결제창 → 결제 접수 → 실제 FDS 평가 → 모의 승인까지 실제 흐름을 탄다
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class CheckoutAddedCardFdsMariaDbTests {
    private static final String DEVICE = "abcdef12-3456";

    @Autowired
    PaymentSessionService checkout;

    @Autowired
    JdbcTemplate jdbc;

    // 한빛카드로 승인된 적 있는 고객이 같은 기기에서 추가 카드로 결제하면 R03만 걸린다(25점, 신규 기기 R02는 없음)
    @Test
    void addedCard_afterApprovedBasicCard_matchesNewCardRule() {
        String customer = customerRef();
        long first = pay(customer, "A");
        approved(first);

        long second = pay(customer, "N");

        assertThat(ruleCodes(second)).containsExactly("R03");
        assertThat(score(second)).isEqualTo(25);
    }

    // 같은 고객이 다음 결제창에서 한빛카드를 다시 쓰면 쓰던 카드라 R03이 걸리지 않는다
    @Test
    void basicCard_again_isNotNewCard() {
        String customer = customerRef();
        long first = pay(customer, "A");
        approved(first);

        long second = pay(customer, "A");

        assertThat(ruleCodes(second)).doesNotContain("R03");
        assertThat(score(second)).isZero();
    }

    // 결제창 세션을 하나 만들고 고른 카드로 결제한다. 결제창마다 새 세션이라 추가 카드도 매번 새 카드다
    private long pay(String customer, String card) {
        long merchantId =
                jdbc.queryForObject("SELECT merchant_id FROM merchants WHERE merchant_code='DEMOSHOP'", Long.class);
        var session = checkout.create(
                merchantId,
                UUID.randomUUID().toString(),
                PaymentSessionCreateRequest.builder()
                        .orderRef("CARD-ADD-" + UUID.randomUUID())
                        .customerRef(customer)
                        .amount(18_000L)
                        .orderCreatedAt(Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MICROS))
                        .orderName("카드 추가 확인")
                        .successUrl("http://shop.test/orders/1/payment/donworry/return")
                        .failUrl("http://shop.test/orders/1/payment/fail")
                        .build());
        checkout.pay(session.sessionKey(), new CheckoutResponses.PayRequest(card, DEVICE), "203.0.113.10");
        return jdbc.queryForObject(
                "SELECT payment_id FROM payment_sessions WHERE session_key=?", Long.class, session.sessionKey());
    }

    // 다음 결제의 R03은 승인된 이력만 본다. 모의 승인 결과가 평가 기록에 남을 때까지 기다린다
    private void approved(long paymentId) {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT authorization_result FROM fds_evaluations WHERE payment_id=?",
                                String.class,
                                paymentId))
                        .isEqualTo("APPROVED"));
    }

    // 걸린 규칙만. 평가마다 모든 규칙이 한 줄씩 남고 걸리지 않은 규칙은 NO_MATCH다
    private List<String> ruleCodes(long paymentId) {
        return jdbc.queryForList(
                "SELECT h.rule_code FROM fds_rule_hits h JOIN fds_evaluations e ON e.evaluation_id = h.evaluation_id "
                        + "WHERE e.payment_id=? AND h.outcome='MATCH' ORDER BY h.rule_code",
                String.class,
                paymentId);
    }

    private Integer score(long paymentId) {
        return jdbc.queryForObject(
                "SELECT risk_score FROM fds_evaluations WHERE payment_id=?", Integer.class, paymentId);
    }

    private static String customerRef() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(100_000_000_000L, 999_999_999_999L));
    }
}
