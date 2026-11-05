package com.donworry.fds.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.donworry.fds.entity.OperatorAccessDeniedType;
import com.donworry.fds.entity.OperatorAccessDetection;
import com.donworry.fds.entity.OperatorAccessEvent;
import com.donworry.fds.repository.FdsRulePolicyRepository;
import com.donworry.fds.repository.OperatorAccessDetectionEvidenceRepository;
import com.donworry.fds.repository.OperatorAccessDetectionRepository;
import com.donworry.fds.repository.OperatorAccessEventRepository;
import com.donworry.identity.api.OperatorAccessDenied;
import com.donworry.identity.api.OperatorAccountStatus;
import com.donworry.identity.api.OperatorAccountStatusReader;
import com.donworry.identity.api.OperatorAccountStatusSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class OperatorAccessDetectionTransactionServiceTests {
    @Mock
    private OperatorAccessEventRepository events;

    @Mock
    private OperatorAccessDetectionRepository detections;

    @Mock
    private OperatorAccessDetectionEvidenceRepository evidences;

    @Mock
    private FdsRulePolicyRepository rulePolicies;

    @Mock
    private OperatorAccountStatusReader statusReader;

    @Mock
    private OperatorAccessDetectionActionService actions;

    @Test
    void receive_whenEarlierEventArrivesLate_reevaluatesLaterWindow() {
        var firstAt = Instant.parse("2026-11-05T01:00:00Z");
        var latestAt = firstAt.plusSeconds(240);
        var received = org.mockito.Mockito.mock(OperatorAccessEvent.class);
        var latest = event(5L, latestAt);
        given(received.getUserId()).willReturn(10L);
        given(received.getDeniedType()).willReturn(OperatorAccessDeniedType.OUT_OF_ROLE);
        given(received.getOccurredAt()).willReturn(firstAt);
        given(received.isSuspensionEligible()).willReturn(true);
        given(latest.getUserId()).willReturn(10L);
        given(latest.getDeniedType()).willReturn(OperatorAccessDeniedType.OUT_OF_ROLE);
        given(latest.getLoginId()).willReturn("boss");
        given(latest.getMerchantId()).willReturn(20L);
        var inWindow = List.of(
                event(1L, firstAt),
                event(2L, firstAt.plusSeconds(60)),
                event(3L, firstAt.plusSeconds(120)),
                event(4L, firstAt.plusSeconds(180)),
                latest);
        given(events.findByIdentityEventId(1L)).willReturn(Optional.empty());
        given(events.save(any())).willReturn(received);
        given(rulePolicies.findByRuleCode("SEC_OPERATOR_OUT_OF_ROLE_REPEAT")).willReturn(Optional.empty());
        given(statusReader.findById(10L))
                .willReturn(Optional.of(new OperatorAccountStatusSnapshot(OperatorAccountStatus.ACTIVE, null)));
        given(events.findByUserIdAndDeniedTypeAndSuspensionEligibleTrueAndOccurredAtBetweenOrderByOccurredAtAsc(
                        10L, OperatorAccessDeniedType.OUT_OF_ROLE, firstAt, firstAt.plusSeconds(300)))
                .willReturn(List.of(latest));
        given(events.findByUserIdAndDeniedTypeAndSuspensionEligibleTrueAndOccurredAtBetweenOrderByOccurredAtAsc(
                        10L, OperatorAccessDeniedType.OUT_OF_ROLE, latestAt.minusSeconds(300), latestAt))
                .willReturn(inWindow);
        given(detections.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));
        var service = new OperatorAccessDetectionTransactionService(
                events,
                detections,
                evidences,
                rulePolicies,
                statusReader,
                actions,
                Clock.fixed(latestAt, ZoneOffset.UTC));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.receive(new OperatorAccessDenied(
                    1L,
                    10L,
                    "boss",
                    20L,
                    "GET",
                    "/security/transactions",
                    com.donworry.identity.api.OperatorAccessDeniedType.OUT_OF_ROLE,
                    firstAt,
                    "127.0.0.1",
                    true));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        var detection = ArgumentCaptor.forClass(OperatorAccessDetection.class);
        verify(detections).saveAndFlush(detection.capture());
        org.assertj.core.api.Assertions.assertThat(detection.getValue().getWindowEndedAt())
                .isEqualTo(latestAt);
    }

    @Test
    void receive_whenCrossSiteGet_isStoredButDoesNotTriggerSuspensionDetection() {
        var occurredAt = Instant.parse("2026-11-05T01:00:00Z");
        var received = org.mockito.Mockito.mock(OperatorAccessEvent.class);
        given(events.findByIdentityEventId(1L)).willReturn(Optional.empty());
        given(events.save(any())).willReturn(received);
        given(received.isSuspensionEligible()).willReturn(false);
        var service = new OperatorAccessDetectionTransactionService(
                events,
                detections,
                evidences,
                rulePolicies,
                statusReader,
                actions,
                Clock.fixed(occurredAt, ZoneOffset.UTC));

        service.receive(new OperatorAccessDenied(
                1L,
                10L,
                "boss",
                20L,
                "GET",
                "/api/admin/users",
                com.donworry.identity.api.OperatorAccessDeniedType.OUT_OF_ROLE,
                occurredAt,
                "127.0.0.1",
                false));

        verify(events).save(any(OperatorAccessEvent.class));
        verifyNoInteractions(detections, evidences, rulePolicies, statusReader, actions);
    }

    private OperatorAccessEvent event(Long identityEventId, Instant occurredAt) {
        var event = org.mockito.Mockito.mock(OperatorAccessEvent.class);
        given(event.getIdentityEventId()).willReturn(identityEventId);
        given(event.getOccurredAt()).willReturn(occurredAt);
        return event;
    }
}
