package com.donworry.settlement.repository;

import com.donworry.settlement.entity.MerchantPayoutItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantPayoutItemRepository extends JpaRepository<MerchantPayoutItem, Long> {
    List<MerchantPayoutItem> findByPayoutIdOrderBySettlementId(Long payoutId);
}
