package com.donworry.settlement.repository;

import com.donworry.settlement.entity.MerchantPayoutItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantPayoutItemRepository extends JpaRepository<MerchantPayoutItem, Long> {
    boolean existsBySettlementId(Long settlementId);

    Optional<MerchantPayoutItem> findBySettlementId(Long settlementId);

    List<MerchantPayoutItem> findByPayoutIdOrderBySettlementId(Long payoutId);
}
