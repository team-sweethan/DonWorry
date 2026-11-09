package com.donworry.fds.repository;

import com.donworry.fds.entity.OperatorAccessDetectionEvidence;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperatorAccessDetectionEvidenceRepository
        extends JpaRepository<OperatorAccessDetectionEvidence, Long> {
    List<OperatorAccessDetectionEvidence> findByDetectionIdOrderByOccurredAtAsc(Long detectionId);

    // 목록 한 쪽의 탐지들에 대해 근거 요청 경로별 수를 한 번에 센다 (줄마다 따로 부르지 않게)
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT evidence.detection_id AS detectionId,
                   event.http_method AS httpMethod,
                   event.path AS path,
                   COUNT(*) AS deniedCount
            FROM fds_operator_access_detection_evidences evidence
            JOIN fds_operator_access_events event
              ON event.operator_access_event_id = evidence.operator_access_event_id
            WHERE evidence.detection_id IN (:detectionIds)
            GROUP BY evidence.detection_id, event.http_method, event.path
            """, nativeQuery = true)
    List<OperatorAccessDetectionPathCountProjection> countPathsByDetectionIds(java.util.Collection<Long> detectionIds);

    @org.springframework.data.jpa.repository.Query(
            "select distinct evidence.identityEventId from OperatorAccessDetectionEvidence evidence")
    List<Long> findAllIdentityEventIds();
}
