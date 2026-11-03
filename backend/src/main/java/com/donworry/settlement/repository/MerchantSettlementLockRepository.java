package com.donworry.settlement.repository;

import com.donworry.settlement.entity.MerchantSettlementLock;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface MerchantSettlementLockRepository extends JpaRepository<MerchantSettlementLock, Long> {
    @Modifying
    @Query(
            value =
                    "INSERT INTO merchant_settlement_locks(merchant_id,created_at) VALUES (:merchant,:now) ON DUPLICATE KEY UPDATE merchant_id=VALUES(merchant_id)",
            nativeQuery = true)
    int ensurePresent(@Param("merchant") Long merchantId, @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM MerchantSettlementLock l WHERE l.merchantId=:merchant")
    Optional<MerchantSettlementLock> findForUpdate(@Param("merchant") Long merchantId);
}
