package com.donworry.fds.service;

import com.donworry.fds.api.*;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 개발·시연에서만 ERROR 평가와 P2 분석 완료 흐름을 재현한다.
@Service
@RequiredArgsConstructor
public class FdsMockFailureService {

    private static final UUID EVALUATION_REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID FAILURE_REPORT_ID = UUID.fromString("00000000-0000-0000-0000-000000000104");
    private static final UUID PAYMENT_IDEMPOTENCY_KEY = UUID.fromString("00000000-0000-0000-0000-000000000204");
    private static final UUID REEVALUATION_REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID REEVALUATION_RETRY_ID = UUID.fromString("00000000-0000-0000-0000-000000000304");
    private static final long PAYMENT_ID = 900_004L;
    private static final long MERCHANT_ID = 1L;
    private static final long PAYMENT_REQUEST_ID = 900_004L;
    private static final String CUSTOMER_REF = "hong-gildong";
    private static final String DEVICE_ID = "device-mock-hong-gildong";
    private static final String CARD_TOKEN = "card-token-mock-4242";
    private static final String CARD_DISPLAY_LABEL = "VISA •••• 4242";
    private static final String IP_COUNTRY_CODE = "KR";
    private static final String IP_ADDRESS = "203.0.113.10";
    private static final String BUYER_EMAIL = "hong-gildong@example.test";
    private static final String ERROR_CODE = "TRANSIENT_MODEL_UNAVAILABLE";

    private final FdsEvaluationRepository evaluations;
    private final FdsFailureReporter failureReporter;
    private final FdsReevaluationRequester reevaluationRequester;

    @Transactional
    public FdsEvaluationResponse createMockError() {
        var requestedAt = Instant.now();
        var request = new FdsEvaluationRequest(
                EVALUATION_REQUEST_ID,
                PAYMENT_ID,
                MERCHANT_ID,
                CUSTOMER_REF,
                100_000L,
                requestedAt,
                DEVICE_ID,
                CARD_TOKEN,
                CARD_DISPLAY_LABEL,
                IP_COUNTRY_CODE,
                IP_ADDRESS,
                BUYER_EMAIL);
        var evaluation = evaluations
                .findByEvaluationRequestId(EVALUATION_REQUEST_ID)
                .orElseGet(() -> evaluations.saveAndFlush(FdsEvaluation.failed(request, ERROR_CODE, Instant.now())));

        failureReporter.report(new FdsEvaluationFailureReport(
                FAILURE_REPORT_ID,
                EVALUATION_REQUEST_ID,
                EVALUATION_REQUEST_ID,
                evaluation.getEvaluationId(),
                PAYMENT_ID,
                MERCHANT_ID,
                CUSTOMER_REF,
                100_000L,
                evaluation.getPaymentRequestedAt(),
                evaluation.getDeviceId(),
                evaluation.getCardToken(),
                evaluation.getCardDisplayLabel(),
                evaluation.getIpCountryCode(),
                evaluation.getIpAddress(),
                evaluation.getBuyerEmail(),
                FdsResult.ERROR,
                ERROR_CODE,
                "시연용 FDS 내부 모델 처리 실패",
                evaluation.getPaymentRequestedAt(),
                evaluation.getEvaluatedAt(),
                "PENDING",
                "NEEDS_REVIEW",
                PAYMENT_REQUEST_ID,
                PAYMENT_IDEMPOTENCY_KEY,
                "mock-order-error",
                null,
                1));

        return new FdsEvaluationResponse(
                evaluation.getEvaluationRequestId(),
                evaluation.getEvaluationId(),
                evaluation.getResult(),
                evaluation.getRiskScore(),
                evaluation.getEvaluatedAt(),
                evaluation.getErrorCode());
    }

    // P5가 분석 완료 이벤트를 받고 같은 결제의 재탐지를 요청한 시연 경로다.
    public FdsEvaluationResponse reevaluateMockError() {
        var original = evaluations
                .findByEvaluationRequestId(EVALUATION_REQUEST_ID)
                .orElseThrow(() -> new IllegalStateException("먼저 ERROR 케이스를 생성해야 합니다"));
        return reevaluationRequester.reevaluate(new FdsReevaluationRequest(
                EVALUATION_REQUEST_ID,
                REEVALUATION_REQUEST_ID,
                PAYMENT_ID,
                MERCHANT_ID,
                CUSTOMER_REF,
                100_000L,
                original.getPaymentRequestedAt(),
                original.getDeviceId(),
                original.getCardToken(),
                original.getCardDisplayLabel(),
                original.getIpCountryCode(),
                original.getIpAddress(),
                original.getBuyerEmail(),
                REEVALUATION_RETRY_ID));
    }
}
