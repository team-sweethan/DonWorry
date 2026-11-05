package com.donworry.fds.repository;

import com.donworry.fds.entity.OperatorAccessDetectionEvidence;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperatorAccessDetectionEvidenceRepository
        extends JpaRepository<OperatorAccessDetectionEvidence, Long> {
    List<OperatorAccessDetectionEvidence> findByDetectionIdOrderByOccurredAtAsc(Long detectionId);

    @org.springframework.data.jpa.repository.Query(
            "select distinct evidence.identityEventId from OperatorAccessDetectionEvidence evidence")
    List<Long> findAllIdentityEventIds();
}
