package com.donworry.fds.repository;

import com.donworry.fds.api.FdsFailureDisposition;
import com.donworry.fds.entity.FdsFailureAnalysis;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FdsFailureAnalysisRepository extends JpaRepository<FdsFailureAnalysis, Long> {

    Optional<FdsFailureAnalysis> findByFailureReportId(UUID failureReportId);

    boolean existsByOriginalEvaluationRequestIdAndDisposition(
            UUID originalEvaluationRequestId, FdsFailureDisposition disposition);
}
