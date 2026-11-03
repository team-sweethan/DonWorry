package com.donworry.settlement.repository;

import com.donworry.settlement.entity.Settlement;
import com.donworry.settlement.entity.SettlementStatus;
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

    @Query("""
            SELECT COALESCE(SUM(s.settlementAmount), 0)
            FROM Settlement s
            WHERE s.merchantId = :merchantId AND s.status = :status
            """)
    Long sumAmountByMerchantIdAndStatus(@Param("merchantId") Long merchantId, @Param("status") SettlementStatus status);

    @Query("""
            SELECT s.settlementId AS settlementId, s.captureId AS captureId, s.paymentId AS paymentId,
                   c.orderId AS orderId, s.grossAmount AS grossAmount, s.cardFeeAmount AS cardFeeAmount,
                   s.pgFeeAmount AS pgFeeAmount, s.settlementAmount AS settlementAmount, s.status AS status,
                   s.expectedSettlementAt AS expectedSettlementAt, s.scheduledAt AS scheduledAt, s.completedAt AS completedAt,
                   f.scheduledFulfillmentAt AS scheduledPickupAt, f.fulfilledAt AS pickedUpAt
            FROM Settlement s JOIN Capture c ON s.captureId = c.captureId
            LEFT JOIN OrderFulfillment f ON f.orderId = c.orderId AND f.merchantId = s.merchantId
            WHERE s.merchantId = :merchant
              AND c.merchantId = :merchant
              AND ((s.status = com.donworry.settlement.entity.SettlementStatus.COMPLETED
                    AND s.completedAt >= :from AND s.completedAt < :until)
                OR (s.status = com.donworry.settlement.entity.SettlementStatus.SCHEDULED
                    AND COALESCE(s.scheduledAt, s.expectedSettlementAt) >= :from
                    AND COALESCE(s.scheduledAt, s.expectedSettlementAt) < :until))
            ORDER BY s.settlementId
            """)
    List<SettlementReportProjection> findReport(
            @Param("merchant") Long merchant,
            @Param("from") Instant from,
            @Param("until") Instant until,
            Pageable pageable);

    @Query("""
            SELECT COALESCE(SUM(s.settlementAmount), 0) FROM Settlement s
            WHERE s.merchantId = :merchant AND s.status = com.donworry.settlement.entity.SettlementStatus.SCHEDULED
            AND s.scheduledAt IS NULL AND s.expectedSettlementAt IS NULL
            """)
    Long sumUndated(@Param("merchant") Long merchant);
}
