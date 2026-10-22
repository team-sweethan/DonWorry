package com.donworry.fds.repository;

import com.donworry.fds.entity.FdsEvaluation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FdsEvaluationRepository extends JpaRepository<FdsEvaluation, Long> {

    long countByCustomerRefAndPaymentRequestedAtBetween(String customerRef, Instant windowStart, Instant windowEnd);

    Optional<FdsEvaluation> findByEvaluationRequestId(UUID evaluationRequestId);

    List<FdsEvaluation> findAllByOrderByEvaluatedAtDesc();

    List<FdsEvaluation> findByPaymentIdOrderByEvaluatedAtDesc(Long paymentId);
}
