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

        var request = new FdsEvaluationRequest(UUID.randomUUID(), 101L, 1L, "hong-gildong", 150_000L, requestedAt);

        assertThat(request.merchantId()).isEqualTo(1L);
        assertThat(request.customerRef()).isEqualTo("hong-gildong");
        assertThat(request.paymentRequestedAt()).isEqualTo(requestedAt);
    }

    // 계약 필수값 누락은 P5가 한 종류의 예외로 처리할 수 있게 IllegalArgumentException으로 통일한다.
    @Test
    void request_whenRequiredValueIsNull_throwsIllegalArgumentException() {
        var requestId = UUID.randomUUID();
        var requestedAt = Instant.parse("2026-10-22T01:00:00Z");

        assertThatThrownBy(() -> new FdsEvaluationRequest(null, 101L, 1L, "hong-gildong", 150_000L, requestedAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FdsEvaluationRequest(requestId, null, 1L, "hong-gildong", 150_000L, requestedAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FdsEvaluationRequest(requestId, 101L, null, "hong-gildong", 150_000L, requestedAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FdsEvaluationRequest(requestId, 101L, 0L, "hong-gildong", 150_000L, requestedAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FdsEvaluationRequest(requestId, 101L, -1L, "hong-gildong", 150_000L, requestedAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FdsEvaluationRequest(requestId, 101L, 1L, "hong-gildong", 150_000L, null))
                .isInstanceOf(IllegalArgumentException.class);
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
