package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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

        var result = queryService().list("HIGH", null, null, null, 0, 20);

        assertThat(result.content()).extracting(value -> value.detectionId()).containsExactly(11L, 10L);
        assertThat(result.highRiskCount()).isEqualTo(2);
    }

    // 고위험 탐지 상세를 처음 열면 다른 조치 상태를 건드리지 않는 전용 갱신으로 확인 시각을 기록한다.
    @Test
    void review_existingUnreviewedDetection_updatesOnlyReviewTimestamp() {
        given(detections.reviewIfUnreviewed(org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.any()))
                .willReturn(1);

        queryService().review(10L);

        verify(detections)
                .reviewIfUnreviewed(
                        org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.any(Instant.class));
        verify(detections, never()).existsById(10L);
    }

    // 이미 확인한 탐지를 다시 열어도 성공하되, 없는 탐지와 구분한다.
    @Test
    void review_whenAlreadyReviewed_keepsIdempotentSuccess() {
        given(detections.reviewIfUnreviewed(org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.any()))
                .willReturn(0);
        given(detections.existsById(10L)).willReturn(true);

        queryService().review(10L);

        verify(detections).existsById(10L);
    }

    // 갱신할 행도 기존 행도 없으면 이전 계약대로 404를 반환한다.
    @Test
    void review_whenDetectionDoesNotExist_throwsNotFound() {
        given(detections.reviewIfUnreviewed(org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.any()))
                .willReturn(0);
        given(detections.existsById(10L)).willReturn(false);

        assertThatThrownBy(() -> queryService().review(10L))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }

    // 배지는 검토 시각이 비어 있는 고위험 탐지만 서버에서 센다.
    @Test
    void unreviewedCount_returnsRepositoryCount() {
        given(detections.countByReviewedAtIsNull()).willReturn(3L);

        assertThat(queryService().unreviewedCount()).isEqualTo(3L);
    }

    @Test
    void list_highRiskSignal_includesItsReviewTimestamp() {
        var reviewedAt = Instant.parse("2026-11-05T02:30:00Z");
        var signal = highRiskSignal(10L, Instant.parse("2026-11-05T01:00:00Z"));
        given(signal.getReviewedAt()).willReturn(reviewedAt);
        given(merchants.findAll()).willReturn(List.of());
        given(detections.findLatestSignals()).willReturn(List.of(signal));
        given(events.findLatestLowRiskSignals()).willReturn(List.of());

        var result = queryService().list("HIGH", null, null, null, 0, 20);

        assertThat(result.content()).extracting(value -> value.reviewedAt()).containsExactly(reviewedAt);
    }

    // 목록 줄의 막힌 경로는 근거 요청 중 가장 많이 막힌 경로로 채운다 (한 번의 집계로).
    @Test
    void list_highRiskSignal_fillsMostFrequentEvidencePath() {
        var signal = highRiskSignal(10L, Instant.parse("2026-11-05T01:00:00Z"));
        given(merchants.findAll()).willReturn(List.of());
        given(detections.findLatestSignals()).willReturn(List.of(signal));
        given(events.findLatestLowRiskSignals()).willReturn(List.of());
        var users = pathCount(10L, "GET", "/api/admin/users", 1);
        var merchantsPath = pathCount(10L, "GET", "/api/admin/merchants", 2);
        given(evidences.countPathsByDetectionIds(java.util.Set.of(10L))).willReturn(List.of(users, merchantsPath));

        var result = queryService().list("HIGH", null, null, null, 0, 20);

        assertThat(result.content())
                .extracting(value -> value.blockedPath())
                .containsExactly("GET /api/admin/merchants");
    }

    // 룰 거르기는 목록과 탭 건수에 같이 적용된다.
    @Test
    void list_whenRuleFilter_keepsOnlyThatRuleAndCountsIt() {
        var outOfRole = highRiskSignal(10L, Instant.parse("2026-11-05T01:00:00Z"));
        var missing =
                highRiskSignal(11L, Instant.parse("2026-11-05T02:00:00Z"), "SEC_OPERATOR_PERMISSION_MISSING_REPEAT");
        given(merchants.findAll()).willReturn(List.of());
        given(detections.findLatestSignals()).willReturn(List.of(outOfRole, missing));
        given(events.findLatestLowRiskSignals()).willReturn(List.of());

        var result = queryService().list("HIGH", "SEC_OPERATOR_PERMISSION_MISSING_REPEAT", null, null, 0, 20);

        assertThat(result.content()).extracting(value -> value.detectionId()).containsExactly(11L);
        assertThat(result.highRiskCount()).isEqualTo(1);
    }

    // 계정 거르기는 정확 일치 — boss를 고르면 boss2 탐지는 섞이지 않는다.
    @Test
    void list_whenLoginIdFilter_matchesExactAccountOnly() {
        var boss = highRiskSignal(10L, Instant.parse("2026-11-05T01:00:00Z"));
        var boss2 =
                highRiskSignal(11L, Instant.parse("2026-11-05T02:00:00Z"), "SEC_OPERATOR_OUT_OF_ROLE_REPEAT", "boss2");
        given(merchants.findAll()).willReturn(List.of());
        given(detections.findLatestSignals()).willReturn(List.of(boss, boss2));
        given(events.findLatestLowRiskSignals()).willReturn(List.of());

        var result = queryService().list("HIGH", null, "boss", null, 0, 20);

        assertThat(result.content()).extracting(value -> value.detectionId()).containsExactly(10L);
    }

    // 계정별 보기는 같은 계정 탐지를 한 묶음으로, 묶음 단위로 쪽을 나눈다.
    @Test
    void accounts_groupsByLoginIdAndPagesByGroup() {
        var bossOld = highRiskSignal(10L, Instant.parse("2026-11-05T01:00:00Z"));
        var other =
                highRiskSignal(12L, Instant.parse("2026-11-05T02:00:00Z"), "SEC_OPERATOR_OUT_OF_ROLE_REPEAT", "moon");
        var bossNew = highRiskSignal(11L, Instant.parse("2026-11-05T03:00:00Z"));
        given(merchants.findAll()).willReturn(List.of());
        given(detections.findLatestSignals()).willReturn(List.of(bossOld, other, bossNew));
        given(events.findLatestLowRiskSignals()).willReturn(List.of());

        var first = queryService().accounts("HIGH", null, null, null, 0, 1);

        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(first.content()).singleElement().satisfies(group -> {
            assertThat(group.loginId()).isEqualTo("boss");
            assertThat(group.detectionCount()).isEqualTo(2);
            assertThat(group.eventCount()).isEqualTo(10);
            assertThat(group.lastOccurredAt()).isEqualTo(Instant.parse("2026-11-05T03:00:00Z"));
            assertThat(group.signals()).extracting(value -> value.detectionId()).containsExactly(11L, 10L);
        });
    }

    private com.donworry.fds.repository.OperatorAccessDetectionPathCountProjection pathCount(
            Long detectionId, String method, String path, long count) {
        var row =
                org.mockito.Mockito.mock(com.donworry.fds.repository.OperatorAccessDetectionPathCountProjection.class);
        given(row.getDetectionId()).willReturn(detectionId);
        org.mockito.Mockito.lenient().when(row.getHttpMethod()).thenReturn(method);
        org.mockito.Mockito.lenient().when(row.getPath()).thenReturn(path);
        given(row.getDeniedCount()).willReturn(count);
        return row;
    }

    // 요약은 DB가 센 날짜별 수를 7일 칸에 채우고, 빈 날은 0으로 둔다.
    @Test
    void summary_fillsMissingDaysWithZeroAndSumsTotal() {
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        var todayCount = dailyCount(today, 3);
        var topPath = pathCount("GET", "/api/admin/users", 3);
        given(events.countDailySince(org.mockito.ArgumentMatchers.anyString())).willReturn(List.of(todayCount));
        given(events.countPathsSince(org.mockito.ArgumentMatchers.anyString())).willReturn(List.of(topPath));
        given(events.countAccountsSince(org.mockito.ArgumentMatchers.anyString()))
                .willReturn(2L);

        var result = queryService().summary();

        assertThat(result.deniedCount()).isEqualTo(3);
        assertThat(result.accountCount()).isEqualTo(2);
        assertThat(result.daily()).extracting(value -> value.count()).containsExactly(0L, 0L, 0L, 0L, 0L, 0L, 3L);
        assertThat(result.daily().getLast().date()).isEqualTo(today);
        assertThat(result.topPaths())
                .extracting(value -> value.httpMethod() + " " + value.path() + " " + value.count())
                .containsExactly("GET /api/admin/users 3");
    }

    private com.donworry.fds.repository.OperatorAccessDailyCountProjection dailyCount(
            java.time.LocalDate day, long count) {
        var row = org.mockito.Mockito.mock(com.donworry.fds.repository.OperatorAccessDailyCountProjection.class);
        given(row.getDay()).willReturn(day);
        given(row.getDeniedCount()).willReturn(count);
        return row;
    }

    private com.donworry.fds.repository.OperatorAccessPathCountProjection pathCount(
            String method, String path, long count) {
        var row = org.mockito.Mockito.mock(com.donworry.fds.repository.OperatorAccessPathCountProjection.class);
        given(row.getHttpMethod()).willReturn(method);
        given(row.getPath()).willReturn(path);
        given(row.getDeniedCount()).willReturn(count);
        return row;
    }

    private OperatorAccessHighRiskSignalProjection highRiskSignal(Long detectionId, Instant occurredAt) {
        return highRiskSignal(detectionId, occurredAt, "SEC_OPERATOR_OUT_OF_ROLE_REPEAT");
    }

    private OperatorAccessHighRiskSignalProjection highRiskSignal(
            Long detectionId, Instant occurredAt, String ruleCode) {
        return highRiskSignal(detectionId, occurredAt, ruleCode, "boss");
    }

    private OperatorAccessHighRiskSignalProjection highRiskSignal(
            Long detectionId, Instant occurredAt, String ruleCode, String loginId) {
        var signal = org.mockito.Mockito.mock(OperatorAccessHighRiskSignalProjection.class);
        given(signal.getDetectionId()).willReturn(detectionId);
        given(signal.getLoginId()).willReturn(loginId);
        given(signal.getMerchantId()).willReturn(2L);
        given(signal.getRuleCode()).willReturn(ruleCode);
        given(signal.getEventCount()).willReturn(5);
        given(signal.getActionStatus()).willReturn("COMPLETED");
        given(signal.getReviewedAt()).willReturn(null);
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
