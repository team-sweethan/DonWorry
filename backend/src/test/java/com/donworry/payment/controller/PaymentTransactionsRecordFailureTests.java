package com.donworry.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.SessionUserTestSupport;
import com.donworry.identity.api.StaffAccessRecorder;
import com.donworry.identity.api.StaffSummary;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.repository.PaymentRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 조회 기록이 실패하면 거래 목록 · 상세를 내보내지 않는다 (fail closed, ADR-0082 4절 · #615 · #732)
// 기록 창구는 트랜잭션 프록시라 스파이로는 실패를 흉내 낼 수 없어 이 클래스만 대역으로 바꾼다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("mariadb")
@SpringBootTest
@AutoConfigureMockMvc
@Import(MariaDbTestContainerConfig.class)
class PaymentTransactionsRecordFailureTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    PaymentRepository payments;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    StaffAccessRecorder accessRecorder;

    @Test
    void list_whenAllowedRecordFails_doesNotReturnList() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String customerRef = "t615-cust-" + suffix;
        String orderRef = "t615-order-" + suffix;
        // 공유 DB의 다른 가맹점 결제 수를 바꾸지 않게 쓰지 않는 가맹점 ID로 넣는다
        long merchantId = jdbc.queryForObject(
                "SELECT GREATEST(COALESCE((SELECT MAX(merchant_id) FROM merchants), 0), COALESCE((SELECT MAX(merchant_id) FROM payments), 0)) + 1",
                Long.class);
        payments.save(Payment.pending(
                merchantId,
                customerRef,
                orderRef,
                1000L,
                Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofHours(1))));
        willThrow(new IllegalStateException("기록 저장 실패")).given(accessRecorder).recordAllowed(any());

        var response = mvc.perform(get("/api/payment-transactions")
                        .param("customerRef", customerRef)
                        .with(SessionUserTestSupport.sessionUser(StaffSummary.builder()
                                .userId(7L)
                                .role("SECURITY_STAFF")
                                .build())))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString())
                .contains("PAYMENT_TRANSACTIONS_INTERNAL_ERROR")
                .doesNotContain("\"items\"")
                .doesNotContain(orderRef);
    }

    // 상세 열람 기록이 실패했는데 결제 정보가 나가면 기록 없는 열람이 생긴다 (#732, ADR-0082 4절)
    @Test
    void detail_whenAccessRecordFails_returnsNoPaymentData() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String orderRef = "t732-order-" + suffix;
        long merchantId = jdbc.queryForObject(
                "SELECT GREATEST(COALESCE((SELECT MAX(merchant_id) FROM merchants), 0), COALESCE((SELECT MAX(merchant_id) FROM payments), 0)) + 1",
                Long.class);
        Payment payment = payments.save(Payment.pending(
                merchantId,
                "t732-cust-" + suffix,
                orderRef,
                1000L,
                Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofHours(1))));
        willThrow(new IllegalStateException("기록 저장 실패")).given(accessRecorder).recordAllowed(any());

        var response = mvc.perform(get("/api/payment-transactions/{paymentId}", payment.getPaymentId())
                        .with(SessionUserTestSupport.sessionUser(StaffSummary.builder()
                                .userId(7L)
                                .role("SECURITY_STAFF")
                                .build())))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString())
                .contains("PAYMENT_TRANSACTIONS_INTERNAL_ERROR")
                .doesNotContain(orderRef);
    }
}
