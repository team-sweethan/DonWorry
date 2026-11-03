package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.settlement.repository.SettlementRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class SettlementFeeMariaDbTests {
    private static final long MERCHANT = 994001L;
    private static final long PAYMENT = 9940001L;

    @MockitoBean
    com.donworry.payment.service.PaymentCaptureService paymentCaptureService;

    @Autowired
    CaptureService captures;

    @Autowired
    SettlementRepository settlements;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void createMerchants() {
        for (long id : new long[] {MERCHANT, MERCHANT + 1}) {
            jdbc.update(
                    "INSERT INTO merchants(merchant_id,merchant_code,display_name,status,pg_fee_rate,created_at) VALUES (?,?,?,'INACTIVE',?,UTC_TIMESTAMP(6))",
                    id,
                    "P6-RATE-" + id,
                    "P6 fee test",
                    id == MERCHANT ? "0.012700" : "0.030000");
        }
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM settlements WHERE payment_id IN (?, ?, ?)", PAYMENT, PAYMENT + 1, PAYMENT + 2);
        jdbc.update("DELETE FROM captures WHERE payment_id IN (?, ?, ?)", PAYMENT, PAYMENT + 1, PAYMENT + 2);
        jdbc.update("DELETE FROM order_fulfillments WHERE order_id LIKE 'P6-RATE-%'");
        jdbc.update("DELETE FROM merchants WHERE merchant_id IN (?, ?)", MERCHANT, MERCHANT + 1);
    }

    // 실제 P1 조회를 통해 가맹점마다 다른 수수료율을 같은 주문번호의 정산에 적용한다.
    @Test
    void processCapture_whenMerchantRatesDiffer_savesEachFeeSnapshot() {
        captures.processCapture(PAYMENT, MERCHANT, "P6-RATE-SHARED", 35500L);
        captures.processCapture(PAYMENT + 1, MERCHANT + 1, "P6-RATE-SHARED", 35500L);
        var first = settlements.findByPaymentId(PAYMENT).orElseThrow();
        var second = settlements.findByPaymentId(PAYMENT + 1).orElseThrow();
        assertThat(first.getPgFeeRate()).isEqualByComparingTo("0.0127");
        assertThat(first.getPgFeeAmount()).isEqualTo(450L);
        assertThat(first.getSettlementAmount()).isEqualTo(34340L);
        assertThat(second.getPgFeeRate()).isEqualByComparingTo("0.03");
        assertThat(second.getPgFeeAmount()).isEqualTo(1065L);
        assertThat(second.getSettlementAmount()).isEqualTo(33725L);
    }

    // 설정 변경은 새 정산에만 적용하고 중복 매입 요청으로 기존 정산을 재계산하지 않는다.
    @Test
    void processCapture_whenRateChanges_keepsExistingAndAppliesNewRate() {
        captures.processCapture(PAYMENT, MERCHANT, "P6-RATE-OLD", 35500L);
        jdbc.update("UPDATE merchants SET pg_fee_rate=0 WHERE merchant_id=?", MERCHANT);
        captures.processCapture(PAYMENT, MERCHANT, "P6-RATE-OLD", 35500L);
        captures.processCapture(PAYMENT + 1, MERCHANT, "P6-RATE-NEW", 35500L);
        var old = settlements.findByPaymentId(PAYMENT).orElseThrow();
        var fresh = settlements.findByPaymentId(PAYMENT + 1).orElseThrow();
        assertThat(old.getPgFeeRate()).isEqualByComparingTo("0.0127");
        assertThat(old.getSettlementAmount()).isEqualTo(34340L);
        assertThat(fresh.getPgFeeRate()).isEqualByComparingTo("0");
        assertThat(fresh.getPgFeeAmount()).isZero();
        assertThat(fresh.getSettlementAmount()).isEqualTo(34790L);
    }

    // 가맹점이 없으면 임시 1%로 성공시키지 않고 매입과 주문 연결까지 함께 롤백한다.
    @Test
    void processCapture_whenMerchantMissing_rollsBackWithoutDefaultRate() {
        assertThatThrownBy(() -> captures.processCapture(PAYMENT + 2, MERCHANT + 2, "P6-RATE-MISSING", 35500L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(settlements.findByPaymentId(PAYMENT + 2)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM captures WHERE payment_id=?", Long.class, PAYMENT + 2))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_fulfillments WHERE merchant_id=? AND order_id=?",
                        Long.class,
                        MERCHANT + 2,
                        "P6-RATE-MISSING"))
                .isZero();
    }
}
