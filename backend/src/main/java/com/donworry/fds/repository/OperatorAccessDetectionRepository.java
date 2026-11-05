package com.donworry.fds.repository;

import com.donworry.fds.entity.OperatorAccessDetection;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperatorAccessDetectionRepository extends JpaRepository<OperatorAccessDetection, Long> {
    List<OperatorAccessDetection> findByDetectedAtGreaterThanEqual(Instant detectedAt);
}
