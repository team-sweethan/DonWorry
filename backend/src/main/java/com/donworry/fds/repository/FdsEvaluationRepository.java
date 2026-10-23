package com.donworry.fds.repository;

import com.donworry.fds.api.FdsResult;
import com.donworry.fds.entity.FdsEvaluation;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface FdsEvaluationRepository extends JpaRepository<FdsEvaluation, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<FdsEvaluation> findByCustomerRefAndPaymentRequestedAtBetween(
            String customerRef, Instant windowStart, Instant windowEnd);

    Optional<FdsEvaluation> findByEvaluationRequestId(UUID evaluationRequestId);

    Page<FdsEvaluation> findAllByOrderByEvaluatedAtDesc(Pageable pageable);

    Page<FdsEvaluation> findByResultOrderByEvaluatedAtDesc(FdsResult result, Pageable pageable);

    Page<FdsEvaluation> findByResultNotOrderByEvaluatedAtDesc(FdsResult result, Pageable pageable);

    long countByResult(FdsResult result);

    List<FdsEvaluation> findByPaymentIdOrderByEvaluatedAtDesc(Long paymentId);
}
