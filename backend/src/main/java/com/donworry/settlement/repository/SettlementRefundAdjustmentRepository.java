package com.donworry.settlement.repository;

import com.donworry.settlement.entity.SettlementRefundAdjustment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRefundAdjustmentRepository extends JpaRepository<SettlementRefundAdjustment, Long> {
    Optional<SettlementRefundAdjustment> findByCancellationId(Long cancellationId);

    Optional<SettlementRefundAdjustment> findBySettlementId(Long settlementId);
}
