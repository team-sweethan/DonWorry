package com.donworry.fds.repository;

import com.donworry.fds.entity.OperatorAccessDetection;
import com.donworry.fds.entity.OperatorAccessDetectionActionStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OperatorAccessDetectionRepository extends JpaRepository<OperatorAccessDetection, Long> {
    List<OperatorAccessDetection> findByDetectedAtGreaterThanEqual(Instant detectedAt);

    List<OperatorAccessDetection> findTop100ByActionStatusOrderByDetectedAtAsc(
            OperatorAccessDetectionActionStatus actionStatus);

    @Query(value = """
            SELECT detection.operator_access_detection_id AS detectionId,
                   detection.login_id AS loginId,
                   detection.merchant_id AS merchantId,
                   detection.rule_code AS ruleCode,
                   detection.event_count AS eventCount,
                   detection.action_status AS actionStatus,
                   detection.detected_at AS occurredAt
            FROM fds_operator_access_detections detection
            WHERE NOT EXISTS (
                SELECT 1
                FROM fds_operator_access_detections newer
                WHERE newer.user_id = detection.user_id
                  AND newer.rule_code = detection.rule_code
                  AND (newer.detected_at > detection.detected_at
                       OR (newer.detected_at = detection.detected_at
                           AND newer.operator_access_detection_id > detection.operator_access_detection_id))
            )
            """, nativeQuery = true)
    List<OperatorAccessHighRiskSignalProjection> findLatestSignals();
}
