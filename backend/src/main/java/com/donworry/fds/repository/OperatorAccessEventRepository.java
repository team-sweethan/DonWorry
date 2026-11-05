package com.donworry.fds.repository;

import com.donworry.fds.entity.OperatorAccessDeniedType;
import com.donworry.fds.entity.OperatorAccessEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OperatorAccessEventRepository extends JpaRepository<OperatorAccessEvent, Long> {
    Optional<OperatorAccessEvent> findByIdentityEventId(Long identityEventId);

    List<OperatorAccessEvent> findByUserIdAndDeniedTypeAndOccurredAtBetweenOrderByOccurredAtAsc(
            Long userId, OperatorAccessDeniedType deniedType, Instant from, Instant to);

    List<OperatorAccessEvent>
            findByUserIdAndDeniedTypeAndSuspensionEligibleTrueAndOccurredAtBetweenOrderByOccurredAtAsc(
                    Long userId, OperatorAccessDeniedType deniedType, Instant from, Instant to);

    @Query(value = """
            SELECT candidate.operator_access_event_id AS sourceEventId,
                   candidate.login_id AS loginId,
                   candidate.merchant_id AS merchantId,
                   candidate.denied_type AS deniedType,
                   COUNT(window_event.operator_access_event_id) AS eventCount,
                   candidate.occurred_at AS occurredAt
            FROM fds_operator_access_events candidate
            JOIN fds_operator_access_events window_event
              ON window_event.user_id = candidate.user_id
             AND window_event.denied_type = candidate.denied_type
             AND window_event.occurred_at BETWEEN
                   DATE_SUB(candidate.occurred_at,
                       INTERVAL CASE candidate.denied_type WHEN 'OUT_OF_ROLE' THEN 300 ELSE 60 END SECOND)
                   AND candidate.occurred_at
             AND NOT EXISTS (
                 SELECT 1
                 FROM fds_operator_access_detection_evidences window_evidence
                 WHERE window_evidence.operator_access_event_id = window_event.operator_access_event_id
             )
            WHERE NOT EXISTS (
                SELECT 1
                FROM fds_operator_access_detection_evidences candidate_evidence
                WHERE candidate_evidence.operator_access_event_id = candidate.operator_access_event_id
            )
              AND NOT EXISTS (
                  SELECT 1
                  FROM fds_operator_access_events newer
                  WHERE newer.user_id = candidate.user_id
                    AND newer.denied_type = candidate.denied_type
                    AND NOT EXISTS (
                        SELECT 1
                        FROM fds_operator_access_detection_evidences newer_evidence
                        WHERE newer_evidence.operator_access_event_id = newer.operator_access_event_id
                    )
                    AND (newer.occurred_at > candidate.occurred_at
                         OR (newer.occurred_at = candidate.occurred_at
                             AND newer.operator_access_event_id > candidate.operator_access_event_id))
              )
            GROUP BY candidate.operator_access_event_id, candidate.login_id, candidate.merchant_id,
                     candidate.denied_type, candidate.occurred_at
            HAVING (candidate.denied_type = 'OUT_OF_ROLE' AND COUNT(window_event.operator_access_event_id) < 5)
                OR (candidate.denied_type = 'PERMISSION_MISSING'
                    AND COUNT(window_event.operator_access_event_id) < 30)
            """, nativeQuery = true)
    List<OperatorAccessLowRiskSignalProjection> findLatestLowRiskSignals();

    @Modifying
    @Query(value = """
                    UPDATE fds_operator_access_events
                    SET client_ip = NULL
                    WHERE client_ip IS NOT NULL
                      AND occurred_at < CAST(:cutoff AS DATETIME(6))
                    ORDER BY occurred_at, operator_access_event_id
                    LIMIT 1000
                    """, nativeQuery = true)
    int clearExpiredClientIpBatch(String cutoff);
}
