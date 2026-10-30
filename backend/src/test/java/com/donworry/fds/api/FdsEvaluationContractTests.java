package com.donworry.fds.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FdsEvaluationContractTests {

    // 고객 식별값과 결제 요청 시각은 반복 결제 룰의 필수 입력이다.
    @Test
    void request_whenRequiredFieldsPresent_createsRequest() {
        var requestedAt = Instant.parse("2026-10-22T01:00:00Z");

        var request = request(UUID.randomUUID(), 101L, 1L, "hong-gildong", 150_000L, requestedAt);

        assertThat(request.merchantId()).isEqualTo(1L);
        assertThat(request.customerRef()).isEqualTo("hong-gildong");
        assertThat(request.paymentRequestedAt()).isEqualTo(requestedAt);
    }

    @Test
    // P5가 전달한 IP와 이메일은 R07 차단 목록 비교를 위해 평가 스냅샷에 보존한다.
    void request_whenBlocklistIdentifiersPresent_normalizesEmail() {
        var request = new FdsEvaluationRequest(
                UUID.randomUUID(),
                101L,
                1L,
                "hong-gildong",
                150_000L,
                Instant.parse("2026-10-22T01:00:00Z"),
                "device-opaque-1",
                "card-token-opaque-1",
                "VISA •••• 1234",
                "kr",
                "203.0.113.10",
                "Hong.Gildong@Example.Test");

        assertThat(request.ipCountryCode()).isEqualTo("KR");
        assertThat(request.cardDisplayLabel()).isEqualTo("VISA •••• 1234");
        assertThat(request.ipAddress()).isEqualTo("203.0.113.10");
        assertThat(request.buyerEmail()).isEqualTo("hong.gildong@example.test");
    }

    // 계약 필수값 누락은 P5가 한 종류의 예외로 처리할 수 있게 IllegalArgumentException으로 통일한다.
    @Test
    void request_whenRequiredValueIsNull_throwsIllegalArgumentException() {
        var requestId = UUID.randomUUID();
        var requestedAt = Instant.parse("2026-10-22T01:00:00Z");

        assertThatThrownBy(() -> request(null, 101L, 1L, "hong-gildong", 150_000L, requestedAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(requestId, null, 1L, "hong-gildong", 150_000L, requestedAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(requestId, 101L, null, "hong-gildong", 150_000L, requestedAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(requestId, 101L, 0L, "hong-gildong", 150_000L, requestedAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(requestId, 101L, -1L, "hong-gildong", 150_000L, requestedAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(requestId, 101L, 1L, "hong-gildong", 150_000L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 신규 평가에는 룰 탐지와 보고서에 필요한 위험 신호 6개가 모두 있어야 한다.
    @Test
    void request_whenRiskSignalIsMissing_throwsIllegalArgumentException() {
        var requestedAt = Instant.parse("2026-10-22T01:00:00Z");

        assertThatThrownBy(() -> new FdsEvaluationRequest(
                        UUID.randomUUID(),
                        101L,
                        1L,
                        "hong-gildong",
                        150_000L,
                        requestedAt,
                        null,
                        "card-token-opaque-1",
                        "VISA •••• 1234",
                        "KR",
                        "203.0.113.10",
                        "hong@example.test"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private FdsEvaluationRequest request(
            UUID requestId, Long paymentId, Long merchantId, String customerRef, long amount, Instant requestedAt) {
        return new FdsEvaluationRequest(
                requestId,
                paymentId,
                merchantId,
                customerRef,
                amount,
                requestedAt,
                "device-opaque-1",
                "card-token-opaque-1",
                "VISA •••• 1234",
                "KR",
                "203.0.113.10",
                "hong@example.test");
    }

    // ERROR는 점수 대신 실패 코드를 반환해 P5가 정상 판정과 구분한다.
    @Test
    void response_whenErrorWithoutScoreAndWithErrorCode_createsResponse() {
        var response = new FdsEvaluationResponse(
                UUID.randomUUID(),
                1L,
                FdsResult.ERROR,
                null,
                Instant.parse("2026-10-22T01:00:01Z"),
                "FDS_EVALUATION_FAILED");

        assertThat(response.result()).isEqualTo(FdsResult.ERROR);
        assertThat(response.riskScore()).isNull();
        assertThat(response.errorCode()).isEqualTo("FDS_EVALUATION_FAILED");
    }

    // 정상 판정은 오류 코드 없이 위험 점수를 반환한다.
    @Test
    void response_whenNonErrorWithoutScore_throwsException() {
        assertThatThrownBy(() ->
                        new FdsEvaluationResponse(UUID.randomUUID(), 1L, FdsResult.REVIEW, null, Instant.now(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
