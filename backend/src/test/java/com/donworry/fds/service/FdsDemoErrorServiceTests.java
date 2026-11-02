package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.donworry.fds.api.FdsEvaluationRequest;
import com.donworry.fds.api.FdsResult;
import com.donworry.fds.entity.FdsEvaluation;
import com.donworry.fds.repository.FdsEvaluationRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FdsDemoErrorServiceTests {

    @Mock
    FdsEvaluationRepository evaluations;

    @Captor
    ArgumentCaptor<FdsEvaluation> evaluationCaptor;

    // 무장한 로컬 시연 오류는 다음 평가 한 건만 ERROR로 저장한다.
    @Test
    void failNext_whenArmed_persistsOneTransientError() {
        var service = new FdsDemoErrorService(evaluations);
        when(evaluations.saveAndFlush(any(FdsEvaluation.class))).thenAnswer(invocation -> {
            var evaluation = invocation.getArgument(0, FdsEvaluation.class);
            org.springframework.test.util.ReflectionTestUtils.setField(evaluation, "evaluationId", 9L);
            return evaluation;
        });

        service.armNextEvaluation();

        var response = service.failNext(request()).orElseThrow();

        assertThat(response.result()).isEqualTo(FdsResult.ERROR);
        assertThat(response.errorCode()).isEqualTo(FdsDemoErrorService.ERROR_CODE);
        verify(evaluations).saveAndFlush(evaluationCaptor.capture());
        assertThat(evaluationCaptor.getValue().getResult()).isEqualTo(FdsResult.ERROR);
        assertThat(service.failNext(request())).isEmpty();
    }

    private FdsEvaluationRequest request() {
        return new FdsEvaluationRequest(
                UUID.randomUUID(),
                1L,
                1L,
                "demo-customer",
                100_000L,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
