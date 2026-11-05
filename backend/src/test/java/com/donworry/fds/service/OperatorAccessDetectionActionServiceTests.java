package com.donworry.fds.service;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.donworry.fds.entity.OperatorAccessDetection;
import com.donworry.fds.entity.OperatorAccessDetectionActionStatus;
import com.donworry.fds.repository.OperatorAccessDetectionRepository;
import com.donworry.identity.api.OperatorAccountStatus;
import com.donworry.identity.api.OperatorAccountStatusReader;
import com.donworry.identity.api.OperatorAccountStatusSnapshot;
import com.donworry.identity.api.OperatorAccountSuspender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OperatorAccessDetectionActionServiceTests {
    @Mock
    private OperatorAccessDetectionRepository detections;

    @Mock
    private OperatorAccountSuspender suspender;

    @Mock
    private OperatorAccountStatusReader statusReader;

    @Mock
    private OperatorAccessDetection detection;

    @Test
    void suspend_whenAccountWasReactivatedAfterDetection_skipsHistoricalAction() {
        var detectedActivation = Instant.parse("2026-11-05T01:00:00Z");
        var currentActivation = detectedActivation.plusSeconds(1);
        given(detections.findById(10L)).willReturn(Optional.of(detection));
        given(detection.getLastActivatedAt()).willReturn(detectedActivation);
        given(statusReader.findById(20L))
                .willReturn(Optional.of(
                        new OperatorAccountStatusSnapshot(OperatorAccountStatus.ACTIVE, currentActivation)));
        var service = new OperatorAccessDetectionActionService(
                detections, suspender, statusReader, Clock.fixed(currentActivation, ZoneOffset.UTC));

        service.suspend(10L, 20L);

        verify(suspender, never()).suspend(20L);
        verify(detection)
                .complete(
                        OperatorAccessDetectionActionStatus.SKIPPED_REACTIVATED,
                        "ACCOUNT_REACTIVATED",
                        currentActivation);
    }
}
