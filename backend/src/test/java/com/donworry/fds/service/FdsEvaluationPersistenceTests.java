package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.repository.FdsBlocklistEntryRepository;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
@Transactional
class FdsEvaluationPersistenceTests {

    @Autowired
    private FdsEvaluationService evaluator;

    @Autowired
    private FdsEvaluationRepository evaluationRepository;

    @Autowired
    private FdsBlocklistEntryRepository blocklistEntries;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        evaluationRepository.deleteAll();
    }

    @Test
    // P5 Mock 결제 세 건에서 마지막 고액 반복 결제를 REVIEW로 저장한다.
    void evaluate_threeMockPayments_persistsReviewWithRuleHits() {
        var firstRequestedAt = Instant.parse("2026-10-22T01:00:00Z");

        evaluator.evaluate(request(1L, "ORDER-001", 50_000L, firstRequestedAt));
        evaluator.evaluate(request(2L, "ORDER-002", 70_000L, firstRequestedAt.plusSeconds(5 * 60)));
        var result = evaluator.evaluate(request(3L, "ORDER-003", 500_000L, firstRequestedAt.plusSeconds(9 * 60)));

        assertThat(result.result()).isEqualTo(FdsResult.REVIEW);
        assertThat(result.riskScore()).isEqualTo(60);
        assertThat(evaluationRepository.findAll()).hasSize(3).anySatisfy(evaluation -> {
            assertThat(evaluation.getCustomerRef()).isEqualTo("hong-gildong");
            assertThat(evaluation.getPaymentRequestedAt()).isEqualTo(firstRequestedAt.plusSeconds(9 * 60));
            assertThat(evaluation.getRiskScore()).isEqualTo(60);
            assertThat(evaluation.getResult()).isEqualTo(FdsResult.REVIEW);
            assertThat(evaluation.getRuleHits()).hasSize(8);
            assertThat(evaluation.getRuleHits()).anySatisfy(hit -> {
                assertThat(hit.getRuleCode()).isEqualTo("R08");
                assertThat(hit.getOutcome().name()).isEqualTo("NO_MATCH");
            });
        });
    }

    @Test
    // 카드 토큰 차단 목록은 대소문자가 다른 토큰을 같은 값으로 취급하지 않는다.
    void blocklist_whenCardTokenDiffersOnlyByCase_doesNotMatch() {
        jdbc.update("""
                INSERT INTO fds_blocklist_entries
                    (identifier_type, identifier_value, reason, active, created_at, expires_at)
                VALUES ('CARD_TOKEN', 'TokenA', 'test', TRUE, UTC_TIMESTAMP(6), NULL)
                """);

        assertThat(blocklistEntries.findByIdentifierTypeAndIdentifierValue("CARD_TOKEN", "TokenA"))
                .hasSize(1);
        assertThat(blocklistEntries.findByIdentifierTypeAndIdentifierValue("CARD_TOKEN", "tokena"))
                .isEmpty();
    }

    private FdsEvaluationRequest request(Long paymentId, String orderRef, long amount, Instant requestedAt) {
        return new FdsEvaluationRequest(UUID.randomUUID(), paymentId, 1L, "hong-gildong", amount, requestedAt);
    }
}
