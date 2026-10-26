package com.donworry.fds.service;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsEvaluationResponse;
import com.donworry.fds.api.FdsEvaluator;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// P5 연동 전, 개발자가 명시적으로 누를 때만 공개 FdsEvaluator 계약을 검증한다.
@Service
public class FdsMockEvaluationService {

    private static final String MOCK_CUSTOMER_REF = "hong-gildong";
    private static final long MOCK_MERCHANT_ID = 1L;
    private static final long HIGH_AMOUNT_THRESHOLD = 150_000L;
    private static final int HIGH_AMOUNT_SCORE = 35;
    private static final int REPEAT_COUNT_THRESHOLD = 3;
    private static final int REPEAT_SCORE = 25;
    private static final Duration REPEAT_WINDOW = Duration.ofMinutes(10);
    private static final Duration MOCK_EVALUATION_INTERVAL = Duration.ofSeconds(1);
    private static final List<MockRequest> MOCK_REQUESTS = List.of(
            new MockRequest("00000000-0000-0000-0000-000000000001", 900_001L, 50_000L, 1, 9),
            new MockRequest("00000000-0000-0000-0000-000000000002", 900_002L, 70_000L, 2, 4),
            new MockRequest("00000000-0000-0000-0000-000000000003", 900_003L, 150_000L, 3, 0));

    private final FdsEvaluator evaluator;
    private final FdsEvaluationRepository evaluationRepository;

    public FdsMockEvaluationService(FdsEvaluator evaluator, FdsEvaluationRepository evaluationRepository) {
        this.evaluator = evaluator;
        this.evaluationRepository = evaluationRepository;
    }

    @Transactional
    public List<FdsEvaluationResponse> createMockEvaluations() {
        var thirdPaymentRequestedAt = Instant.now();
        var responses = new ArrayList<FdsEvaluationResponse>();
        for (var index = 0; index < MOCK_REQUESTS.size(); index++) {
            var mock = MOCK_REQUESTS.get(index);
            responses.add(evaluator.evaluate(request(
                    mock.requestId(),
                    mock.paymentId(),
                    mock.amount(),
                    thirdPaymentRequestedAt.minusSeconds(mock.minutesBeforeLastRequest() * 60L))));
            waitBeforeNextMockEvaluation(index);
        }
        backfillExistingMockSnapshots();
        return responses;
    }

    private void waitBeforeNextMockEvaluation(int index) {
        if (index == MOCK_REQUESTS.size() - 1) {
            return;
        }
        try {
            TimeUnit.MILLISECONDS.sleep(MOCK_EVALUATION_INTERVAL.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Mock 평가 생성이 중단되었습니다.", exception);
        }
    }

    private void backfillExistingMockSnapshots() {
        for (var mock : MOCK_REQUESTS) {
            evaluationRepository
                    .findByEvaluationRequestId(UUID.fromString(mock.requestId()))
                    .ifPresent(evaluation -> {
                        var requestedAt = evaluation.getPaymentRequestedAt();
                        var changed = evaluation.getRuleHits().stream()
                                        .mapToInt(hit -> switch (hit.getRuleCode()) {
                                            case "HIGH_AMOUNT" ->
                                                hit.backfillSnapshotsIfMissing(
                                                                "{\"amountGte\":%d,\"score\":%d}"
                                                                        .formatted(
                                                                                HIGH_AMOUNT_THRESHOLD,
                                                                                HIGH_AMOUNT_SCORE),
                                                                "{\"amount\":%d}".formatted(mock.amount()),
                                                                null,
                                                                null)
                                                        ? 1
                                                        : 0;
                                            case "RAPID_REPEAT_PAYMENT" ->
                                                hit.backfillSnapshotsIfMissing(
                                                                "{\"windowMinutes\":%d,\"attemptCountGte\":%d,\"score\":%d}"
                                                                        .formatted(
                                                                                REPEAT_WINDOW.toMinutes(),
                                                                                REPEAT_COUNT_THRESHOLD,
                                                                                REPEAT_SCORE),
                                                                "{\"attemptCount\":%d}".formatted(mock.attemptCount()),
                                                                requestedAt.minus(REPEAT_WINDOW),
                                                                requestedAt)
                                                        ? 1
                                                        : 0;
                                            default -> 0;
                                        })
                                        .sum()
                                > 0;
                        if (changed) {
                            evaluationRepository.save(evaluation);
                        }
                    });
        }
    }

    private FdsEvaluationRequest request(String requestId, Long paymentId, long amount, Instant paymentRequestedAt) {
        return new FdsEvaluationRequest(
                UUID.fromString(requestId), paymentId, MOCK_MERCHANT_ID, MOCK_CUSTOMER_REF, amount, paymentRequestedAt);
    }

    private record MockRequest(
            String requestId, Long paymentId, long amount, int attemptCount, int minutesBeforeLastRequest) {}
}
