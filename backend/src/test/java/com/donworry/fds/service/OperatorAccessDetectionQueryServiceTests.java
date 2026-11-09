package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.donworry.fds.entity.OperatorAccessDeniedType;
import com.donworry.fds.entity.OperatorAccessDetection;
import com.donworry.fds.entity.OperatorAccessEvent;
import com.donworry.fds.repository.OperatorAccessDetectionEvidenceRepository;
import com.donworry.fds.repository.OperatorAccessDetectionRepository;
import com.donworry.fds.repository.OperatorAccessEventRepository;
import com.donworry.fds.repository.OperatorAccessHighRiskSignalProjection;
import com.donworry.identity.api.MerchantDirectoryReader;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OperatorAccessDetectionQueryServiceTests {

    @Mock
    private OperatorAccessDetectionRepository detections;

    @Mock
    private OperatorAccessDetectionEvidenceRepository evidences;

    @Mock
    private OperatorAccessEventRepository events;

    @Mock
    private MerchantDirectoryReader merchants;

    @Mock
    private OperatorAccessDetection detection;

    // 보안 룰 카탈로그는 최근 7일 탐지 이력으로 적중 횟수와 추이를 만든다.
    @Test
    void ruleCatalog_recentDetection_returnsFrequencyForMatchingRule() {
        var detectedAt = Instant.now();
        given(detections.findByDetectedAtGreaterThanEqual(org.mockito.ArgumentMatchers.any()))
                .willReturn(List.of(detection));
        given(detection.getRuleCode()).willReturn("SEC_OPERATOR_OUT_OF_ROLE_REPEAT");
        given(detection.getDetectedAt()).willReturn(detectedAt);

        var result = queryService().ruleCatalog();

        assertThat(result)
                .extracting(entry -> entry.ruleCode())
                .containsExactly("SEC_OPERATOR_OUT_OF_ROLE_REPEAT", "SEC_OPERATOR_PERMISSION_MISSING_REPEAT");
        assertThat(result.getFirst()).satisfies(entry -> {
            assertThat(entry.recentMatchCount()).isEqualTo(1);
            assertThat(entry.lastMatchedAt()).isEqualTo(detectedAt);
            assertThat(entry.dailyMatchCounts()).hasSize(7);
        });
    }

    // 기준 미도달 접근도 발생 시점의 집계 근거를 상세에서 확인할 수 있다.
    @Test
    void lowRiskDetail_whenBelowThreshold_returnsEventsInTheApplicableWindow() {
        var occurredAt = Instant.parse("2026-11-05T01:00:00Z");
        var event = org.mockito.Mockito.mock(OperatorAccessEvent.class);
        given(event.getIdentityEventId()).willReturn(70L);
        given(event.getUserId()).willReturn(1L);
        given(event.getLoginId()).willReturn("boss");
        given(event.getMerchantId()).willReturn(2L);
        given(event.getDeniedType()).willReturn(OperatorAccessDeniedType.OUT_OF_ROLE);
        given(event.getOccurredAt()).willReturn(occurredAt);
        given(event.getHttpMethod()).willReturn("GET");
        given(event.getPath()).willReturn("/api/admin/users");
        given(evidences.findAllIdentityEventIds()).willReturn(List.of());
        given(events.findById(7L)).willReturn(java.util.Optional.of(event));
        given(events.findByUserIdAndDeniedTypeAndOccurredAtBetweenOrderByOccurredAtAsc(
                        1L, OperatorAccessDeniedType.OUT_OF_ROLE, occurredAt.minusSeconds(300), occurredAt))
                .willReturn(List.of(event));

        var result = queryService().lowRiskDetail(7L);

        assertThat(result).satisfies(detail -> {
            assertThat(detail.riskLevel()).isEqualTo("LOW");
            assertThat(detail.actionStatus()).isEqualTo("NONE");
            assertThat(detail.eventCount()).isEqualTo(1);
            assertThat(detail.evidences())
                    .extracting(value -> value.identityEventId())
                    .containsExactly(70L);
        });
    }

    @Test
    void lowRiskDetail_excludesEventsAlreadyUsedAsHighRiskEvidence() {
        var occurredAt = Instant.parse("2026-11-05T01:00:00Z");
        var lowRiskEvent = event(70L, occurredAt);
        var highRiskEvidence = org.mockito.Mockito.mock(OperatorAccessEvent.class);
        given(highRiskEvidence.getIdentityEventId()).willReturn(71L);
        given(evidences.findAllIdentityEventIds()).willReturn(List.of(71L));
        given(events.findById(7L)).willReturn(java.util.Optional.of(lowRiskEvent));
        given(events.findByUserIdAndDeniedTypeAndOccurredAtBetweenOrderByOccurredAtAsc(
                        1L, OperatorAccessDeniedType.OUT_OF_ROLE, occurredAt.minusSeconds(300), occurredAt))
                .willReturn(List.of(highRiskEvidence, lowRiskEvent));

        var result = queryService().lowRiskDetail(7L);

        assertThat(result.evidences())
                .extracting(value -> value.identityEventId())
                .containsExactly(70L);
    }

    // 같은 계정과 룰에서 새 고위험 탐지가 발생해도 이전 탐지 이력은 목록에 남는다.
    @Test
    void list_whenSameUserAndRuleHaveMultipleHighRiskDetections_returnsAllDetectionHistory() {
        var earlier = highRiskSignal(10L, Instant.parse("2026-11-05T01:00:00Z"));
        var latest = highRiskSignal(11L, Instant.parse("2026-11-05T02:00:00Z"));
        given(merchants.findAll()).willReturn(List.of());
        given(detections.findLatestSignals()).willReturn(List.of(earlier, latest));
        given(events.findLatestLowRiskSignals()).willReturn(List.of());

        var result = queryService().list("HIGH", null, 0, 20);

        assertThat(result.content()).extracting(value -> value.detectionId()).containsExactly(11L, 10L);
        assertThat(result.highRiskCount()).isEqualTo(2);
    }

    private OperatorAccessHighRiskSignalProjection highRiskSignal(Long detectionId, Instant occurredAt) {
        var signal = org.mockito.Mockito.mock(OperatorAccessHighRiskSignalProjection.class);
        given(signal.getDetectionId()).willReturn(detectionId);
        given(signal.getLoginId()).willReturn("boss");
        given(signal.getMerchantId()).willReturn(2L);
        given(signal.getRuleCode()).willReturn("SEC_OPERATOR_OUT_OF_ROLE_REPEAT");
        given(signal.getEventCount()).willReturn(5);
        given(signal.getActionStatus()).willReturn("COMPLETED");
        given(signal.getOccurredAt()).willReturn(occurredAt);
        return signal;
    }

    private OperatorAccessEvent event(Long identityEventId, Instant occurredAt) {
        var event = org.mockito.Mockito.mock(OperatorAccessEvent.class);
        given(event.getIdentityEventId()).willReturn(identityEventId);
        given(event.getUserId()).willReturn(1L);
        given(event.getLoginId()).willReturn("boss");
        given(event.getMerchantId()).willReturn(2L);
        given(event.getDeniedType()).willReturn(OperatorAccessDeniedType.OUT_OF_ROLE);
        given(event.getOccurredAt()).willReturn(occurredAt);
        given(event.getHttpMethod()).willReturn("GET");
        given(event.getPath()).willReturn("/api/admin/users");
        return event;
    }

    private OperatorAccessDetectionQueryService queryService() {
        return new OperatorAccessDetectionQueryService(detections, evidences, events, merchants);
    }
}
