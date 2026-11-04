package com.donworry.settlement.repository;

import com.donworry.settlement.entity.*;
import jakarta.persistence.LockModeType;
import java.time.*;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface MerchantPayoutRepository extends JpaRepository<MerchantPayout, Long> {
    @Modifying
    @Query(value = """
        INSERT INTO merchant_payouts(merchant_id,payout_date,status,origin,settlement_total,refund_deduction_total,payout_amount,created_at,updated_at)
        VALUES(:merchant,:date,'PENDING','BATCH',0,0,0,:now,:now)
        ON DUPLICATE KEY UPDATE merchant_id=merchant_id
        """, nativeQuery = true)
    void ensurePending(
            @Param("merchant") Long merchant, @Param("date") LocalDate date, @Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM MerchantPayout p WHERE p.merchantId=:merchant AND p.payoutDate=:date")
    Optional<MerchantPayout> findForUpdate(@Param("merchant") Long merchant, @Param("date") LocalDate date);

    Optional<MerchantPayout> findByMerchantIdAndPayoutDate(Long merchantId, LocalDate payoutDate);

    boolean existsByMerchantIdAndPayoutDateAndStatus(Long merchantId, LocalDate payoutDate, PayoutStatus status);
}
