package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
@Transactional
class FdsEvaluationQuerySearchTests {

    @Autowired
    private FdsEvaluationQueryService queryService;

    @Autowired
    private FdsEvaluationRepository evaluationRepository;

    @BeforeEach
    void setUp() {
        evaluationRepository.deleteAll();
    }

    // 고객 식별자는 대소문자와 관계없이 일부 문자열로 검색한다.
    @Test
    void list_searchCustomerRef_returnsMatchingEvaluation() {
        save(1001L, "alice-customer", FdsResult.REVIEW, 60, 1);
        save(1002L, "bob-customer", FdsResult.DENY, 80, 2);

        var result = queryService.list(null, false, "ALICE", 0, 10);

        assertThat(result.content()).singleElement().satisfies(summary -> {
            assertThat(summary.paymentId()).isEqualTo(1001L);
            assertThat(summary.customerRef()).isEqualTo("alice-customer");
        });
        assertThat(result.totalElements()).isEqualTo(1);
    }

    // 결제 ID로 검색하면 해당 결제의 평가만 반환한다.
    @Test
    void list_searchPaymentId_returnsMatchingEvaluation() {
        save(2001L, "customer-a", FdsResult.REVIEW, 60, 1);
        save(2002L, "customer-b", FdsResult.DENY, 80, 2);

        var result = queryService.list(null, false, "2002", 0, 10);

        assertThat(result.content()).singleElement().satisfies(summary -> {
            assertThat(summary.paymentId()).isEqualTo(2002L);
            assertThat(summary.result()).isEqualTo(FdsResult.DENY);
        });
    }

    // 검색어와 판정 필터를 함께 적용한 결과도 서버에서 페이지로 나눈다.
    @Test
    void list_searchWithResultFilter_returnsFilteredPage() {
        save(3001L, "search-target", FdsResult.REVIEW, 60, 1);
        save(3002L, "search-target", FdsResult.DENY, 80, 2);
        save(3003L, "search-target", FdsResult.REVIEW, 60, 3);

        var firstPage = queryService.list(FdsResult.REVIEW, false, "target", 0, 1);
        var secondPage = queryService.list(FdsResult.REVIEW, false, "target", 1, 1);

        assertThat(firstPage.totalElements()).isEqualTo(2);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(firstPage.content()).singleElement().satisfies(summary -> {
            assertThat(summary.result()).isEqualTo(FdsResult.REVIEW);
            assertThat(summary.customerRef()).isEqualTo("search-target");
        });
        assertThat(secondPage.content()).singleElement().satisfies(summary -> {
            assertThat(summary.result()).isEqualTo(FdsResult.REVIEW);
            assertThat(summary.evaluationId())
                    .isNotEqualTo(firstPage.content().getFirst().evaluationId());
        });
    }

    private void save(Long paymentId, String customerRef, FdsResult result, int riskScore, long secondOffset) {
        var requestedAt = Instant.parse("2026-10-23T00:00:00Z").plusSeconds(secondOffset);
        var request = new FdsEvaluationRequest(UUID.randomUUID(), paymentId, customerRef, 150_000L, requestedAt);
        evaluationRepository.save(FdsEvaluation.completed(request, result, riskScore, requestedAt.plusSeconds(1)));
    }
}
