package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsLinkedPaymentReader;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

// 연관 결제 조회 조건(가맹점 · 기간)이 실제 DB에서 맞는지 본다 (#352)
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
@Transactional
class FdsLinkedPaymentQueryMariaDbTests {

    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");

    @Autowired
    private FdsLinkedPaymentReader linkedPaymentReader;

    @Autowired
    private FdsEvaluationRepository evaluationRepository;

    @BeforeEach
    void setUp() {
        evaluationRepository.deleteAll();
    }

    // 다른 가맹점 결제는 같은 카드여도 세지 않는다 (기간 밖 결제도 함께 확인)
    @Test
    void findLinked_otherMerchant_notCounted() {
        save(9100L, 1L, "case-customer", NOW.minusSeconds(3600));
        save(9201L, 1L, "other-customer", NOW.minusSeconds(7200));
        save(9202L, 2L, "other-merchant-customer", NOW.minusSeconds(7200));
        save(9203L, 1L, "old-customer", NOW.minusSeconds(40L * 24 * 3600));

        var result =
                linkedPaymentReader.findLinked(1L, "case-customer", List.of(9100L), NOW.minusSeconds(30L * 24 * 3600));

        assertThat(result.card().provided()).isTrue();
        assertThat(result.card().paymentCount()).isEqualTo(1);
        assertThat(result.card().otherCustomerCount()).isEqualTo(1);
    }

    private void save(Long paymentId, Long merchantId, String customerRef, Instant requestedAt) {
        var request = FdsEvaluationRequest.builder()
                .evaluationRequestId(UUID.randomUUID())
                .paymentId(paymentId)
                .merchantId(merchantId)
                .customerRef(customerRef)
                .amount(50_000L)
                .paymentRequestedAt(requestedAt)
                .deviceId("device-linked-test")
                .cardToken("tok_linked_test")
                .cardDisplayLabel("VISA **** 1234")
                .ipCountryCode("KR")
                .ipAddress("203.0.113.10")
                .buyerEmail("linked@example.test")
                .build();
        evaluationRepository.save(FdsEvaluation.completed(request, FdsResult.REVIEW, 60, requestedAt));
    }
}
