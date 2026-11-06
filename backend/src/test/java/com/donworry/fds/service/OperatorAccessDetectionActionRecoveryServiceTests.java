package com.donworry.fds.service;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.donworry.fds.entity.OperatorAccessDetection;
import com.donworry.fds.entity.OperatorAccessDetectionActionStatus;
import com.donworry.fds.repository.OperatorAccessDetectionRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OperatorAccessDetectionActionRecoveryServiceTests {
    @Mock
    private OperatorAccessDetectionRepository detections;

    @Mock
    private OperatorAccessDetectionActionService actions;

    @Mock
    private OperatorAccessDetection detection;

    @Test
    void retryRequestedActions_retriesPersistedRequestedAction() {
        given(detection.getId()).willReturn(10L);
        given(detection.getUserId()).willReturn(20L);
        given(detections.findTop100ByActionStatusOrderByDetectedAtAsc(OperatorAccessDetectionActionStatus.REQUESTED))
                .willReturn(List.of(detection));

        new OperatorAccessDetectionActionRecoveryService(detections, actions).retryRequestedActions();

        verify(actions).suspend(10L, 20L);
    }
}
