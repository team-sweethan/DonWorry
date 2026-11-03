package com.donworry.settlement.repository;

import com.donworry.settlement.entity.SettlementRefundAdjustment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementRefundAdjustmentRepository extends JpaRepository<SettlementRefundAdjustment, Long> {
    Optional<SettlementRefundAdjustment> findByCancellationId(Long cancellationId);

    Optional<SettlementRefundAdjustment> findBySettlementId(Long settlementId);

    @Query("""
            SELECT a FROM SettlementRefundAdjustment a JOIN Settlement s ON s.settlementId=a.settlementId
            WHERE a.merchantId=:merchant AND s.merchantId=:merchant AND a.adjustmentId>:after
              AND a.status=com.donworry.settlement.entity.RefundAdjustmentStatus.PENDING
              AND s.status=com.donworry.settlement.entity.SettlementStatus.SCHEDULED
            ORDER BY a.adjustmentId
            """)
    List<SettlementRefundAdjustment> findPendingUnpaid(
            @Param("merchant") Long merchantId, @Param("after") Long after, Pageable pageable);
}
