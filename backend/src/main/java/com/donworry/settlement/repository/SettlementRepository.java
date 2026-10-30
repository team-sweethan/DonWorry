package com.donworry.settlement.repository;

import com.donworry.settlement.entity.Settlement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findByPaymentId(Long paymentId);

    Optional<Settlement> findByCaptureId(Long captureId);

    boolean existsByPaymentId(Long paymentId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Settlement s WHERE s.settlementId = :id")
    Optional<Settlement> findForCompletion(@Param("id") Long id);

    @Query(
            "SELECT COALESCE(MAX(s.settlementId), 0) FROM Settlement s WHERE s.status = com.donworry.settlement.entity.SettlementStatus.SCHEDULED AND s.scheduledAt <= :cutoff")
    Long completionUpperBound(@Param("cutoff") Instant cutoff);

    @Query("""
            SELECT s.settlementId AS settlementId, s.merchantId AS merchantId, c.orderId AS orderId
            FROM Settlement s JOIN Capture c ON s.captureId = c.captureId
            WHERE s.status = com.donworry.settlement.entity.SettlementStatus.SCHEDULED
              AND s.scheduledAt <= :cutoff AND s.settlementId > :after AND s.settlementId <= :upper
            ORDER BY s.settlementId
            """)
    List<SettlementCompletionCandidate> findCompletionCandidates(
            @Param("cutoff") Instant cutoff, @Param("after") Long after, @Param("upper") Long upper, Pageable pageable);
}
