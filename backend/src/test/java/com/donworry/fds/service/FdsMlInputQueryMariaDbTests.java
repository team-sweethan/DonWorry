package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsMlInputReader;
import com.donworry.fds.api.FdsMlTarget;
import com.donworry.fds.api.FdsMlTransaction;
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

// ML Shadow 입력 조회 조건(첫 비오류 평가 · 시각 · 가맹점)이 실제 DB에서 맞는지 본다 (#409)
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
@Transactional
class FdsMlInputQueryMariaDbTests {

    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");

    @Autowired
    private FdsMlInputReader mlInputReader;

    @Autowired
    private FdsEvaluationRepository evaluationRepository;

    @BeforeEach
    void setUp() {
        evaluationRepository.deleteAll();
    }

    // 결제마다 처음 나온 비오류 평가만 대상이다. 걸러진 평가 때문에 커서가 멈추지 않게 SQL에서 거른 뒤 개수를 센다
    @Test
    void findNewTargets_firstNonErrorPerPayment_only() {
        var first = evaluate(9300L, FdsResult.ALLOW, NOW.minusSeconds(600));
        evaluate(9301L, FdsResult.ERROR, NOW.minusSeconds(590));
        var reevaluated = evaluate(9301L, FdsResult.ALLOW, NOW.minusSeconds(580));
        var single = evaluate(9302L, FdsResult.ALLOW, NOW.minusSeconds(570));
        evaluate(9302L, FdsResult.DENY, NOW.minusSeconds(560));

        var all = mlInputReader.findNewTargets(first.getEvaluationId() - 1, NOW, 10);
        var nextOne = mlInputReader.findNewTargets(first.getEvaluationId(), NOW, 1);
        var afterLast = mlInputReader.findNewTargets(single.getEvaluationId(), NOW, 10);

        assertThat(all)
                .extracting(FdsMlTarget::evaluationId)
                .containsExactly(first.getEvaluationId(), reevaluated.getEvaluationId(), single.getEvaluationId());
        assertThat(nextOne).extracting(FdsMlTarget::evaluationId).containsExactly(reevaluated.getEvaluationId());
        assertThat(afterLast).isEmpty();
    }

    // 대상 결제보다 뒤에 요청된 결제는 고객 이력 · 같은 카드 이력 어디에도 넣지 않는다
    @Test
    void findInput_historyAfterTarget_excluded() {
        var target = save(9400L, 1L, "1:target", "tok_target", NOW.minusSeconds(1800));
        save(9401L, 1L, "1:target", "tok_old", NOW.minusSeconds(3600));
        save(9404L, 1L, "1:target", "tok_late", NOW.minusSeconds(600));
        save(9501L, 1L, "1:other", "tok_target", NOW.minusSeconds(7200));
        save(9502L, 1L, "1:other", "tok_target", NOW.minusSeconds(60));

        var input = mlInputReader.findInput(target.getEvaluationId(), 2_000).orElseThrow();

        assertThat(input.customerHistory())
                .extracting(FdsMlTransaction::paymentId)
                .containsExactly(9401L);
        assertThat(input.sharedCardHistory())
                .extracting(FdsMlTransaction::paymentId)
                .containsExactly(9501L);
    }

    // 고객은 가맹점 + customerRef로 구분한다 — 다른 가맹점의 같은 번호 고객 결제는 고객 이력이 아니다
    @Test
    void findInput_sameRefOtherMerchant_notInCustomerHistory() {
        var target = save(9600L, 1L, "member-7", "tok_6600", NOW.minusSeconds(1800));
        save(9601L, 1L, "member-7", "tok_6601", NOW.minusSeconds(3600));
        save(9602L, 2L, "member-7", "tok_6602", NOW.minusSeconds(3600));

        var input = mlInputReader.findInput(target.getEvaluationId(), 2_000).orElseThrow();

        assertThat(input.customerHistory())
                .extracting(FdsMlTransaction::paymentId)
                .containsExactly(9601L);
    }

    // 같은 카드를 쓴 다른 고객은 같은 가맹점 안에서만 찾는다 (학습 데이터와 같은 범위, ADR-0062)
    @Test
    void findInput_sameCardOtherMerchant_notShared() {
        var target = save(9700L, 1L, "1:card-owner", "tok_shared", NOW.minusSeconds(1800));
        save(9701L, 1L, "1:same-merchant", "tok_shared", NOW.minusSeconds(3600));
        save(9702L, 2L, "2:other-merchant", "tok_shared", NOW.minusSeconds(3600));

        var input = mlInputReader.findInput(target.getEvaluationId(), 2_000).orElseThrow();

        assertThat(input.sharedCardHistory())
                .extracting(FdsMlTransaction::paymentId)
                .containsExactly(9701L);
    }

    private FdsEvaluation evaluate(Long paymentId, FdsResult result, Instant evaluatedAt) {
        var request = request(paymentId, 1L, "1:targets", "tok_" + paymentId, evaluatedAt.minusSeconds(1));
        var evaluation = result == FdsResult.ERROR
                ? FdsEvaluation.failed(request, "TEST_ERROR", evaluatedAt)
                : FdsEvaluation.completed(request, result, 40, evaluatedAt);
        return evaluationRepository.saveAndFlush(evaluation);
    }

    private FdsEvaluation save(Long paymentId, Long merchantId, String customerRef, String cardToken, Instant at) {
        var request = request(paymentId, merchantId, customerRef, cardToken, at);
        return evaluationRepository.saveAndFlush(FdsEvaluation.completed(request, FdsResult.ALLOW, 20, at));
    }

    private static FdsEvaluationRequest request(
            Long paymentId, Long merchantId, String customerRef, String cardToken, Instant requestedAt) {
        return FdsEvaluationRequest.builder()
                .evaluationRequestId(UUID.randomUUID())
                .paymentId(paymentId)
                .merchantId(merchantId)
                .customerRef(customerRef)
                .amount(30_000L)
                .paymentRequestedAt(requestedAt)
                .cardToken(cardToken)
                .deviceId("device-" + paymentId)
                .build();
    }
}
