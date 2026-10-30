package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.donworry.fds.api.FdsEvaluationFailureReport;
import com.donworry.fds.api.FdsFailureReporter;
import com.donworry.fds.api.FdsReevaluationRequester;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FdsMockFailureServiceTests {

    @Mock
    FdsEvaluationRepository evaluations;

    @Mock
    FdsFailureReporter failureReporter;

    @Mock
    FdsReevaluationRequester reevaluationRequester;

    @Captor
    ArgumentCaptor<FdsEvaluationFailureReport> reportCaptor;

    @Test
    // 개발용 ERROR 생성은 P2 오류 분석 계약에 필요한 P5 스냅샷도 함께 보고한다.
    void createMockError_createsErrorAndReportsFailure() {
        when(evaluations.findByEvaluationRequestId(any())).thenReturn(Optional.empty());
        when(evaluations.saveAndFlush(any(FdsEvaluation.class))).thenAnswer(invocation -> {
            var saved = invocation.getArgument(0, FdsEvaluation.class);
            org.springframework.test.util.ReflectionTestUtils.setField(saved, "evaluationId", 4L);
            return saved;
        });

        var response = new FdsMockFailureService(evaluations, failureReporter, reevaluationRequester).createMockError();

        assertThat(response.result()).isEqualTo(FdsResult.ERROR);
        assertThat(response.errorCode()).isEqualTo("TRANSIENT_MODEL_UNAVAILABLE");
        verify(failureReporter).report(reportCaptor.capture());
        assertThat(reportCaptor.getValue().evaluationResult()).isEqualTo(FdsResult.ERROR);
        assertThat(reportCaptor.getValue().errorCode()).isEqualTo("TRANSIENT_MODEL_UNAVAILABLE");
    }

    @Test
    // 재탐지 버튼 경로는 P5가 새 UUID·멱등 키를 만든 요청을 P2 공개 API에 전달한 상황을 재현한다.
    void reevaluateMockError_delegatesToReevaluationRequester() {
        var original = FdsEvaluation.failed(
                new com.donworry.fds.api.FdsEvaluationRequest(
                        java.util.UUID.fromString("00000000-0000-0000-0000-000000000004"),
                        900_004L,
                        1L,
                        "hong-gildong",
                        100_000L,
                        java.time.Instant.parse("2026-10-27T01:00:00Z"),
                        "device-mock-hong-gildong",
                        "card-token-mock-4242",
                        "VISA •••• 4242",
                        "KR",
                        "203.0.113.10",
                        "hong-gildong@example.test"),
                "TRANSIENT_MODEL_UNAVAILABLE",
                java.time.Instant.parse("2026-10-27T01:00:01Z"));
        when(evaluations.findByEvaluationRequestId(any())).thenReturn(Optional.of(original));
        when(reevaluationRequester.reevaluate(any()))
                .thenReturn(new com.donworry.fds.api.FdsEvaluationResponse(
                        java.util.UUID.randomUUID(), 5L, FdsResult.ALLOW, 0, java.time.Instant.now(), null));

        var response =
                new FdsMockFailureService(evaluations, failureReporter, reevaluationRequester).reevaluateMockError();

        assertThat(response.result()).isEqualTo(FdsResult.ALLOW);
        verify(reevaluationRequester).reevaluate(any());
    }
}
