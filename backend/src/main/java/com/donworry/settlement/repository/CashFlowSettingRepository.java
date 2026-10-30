package com.donworry.settlement.repository;

import com.donworry.settlement.entity.CashFlowSetting;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface CashFlowSettingRepository extends JpaRepository<CashFlowSetting, Long> {
    Optional<CashFlowSetting> findByMerchantId(Long merchantId);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM CashFlowSetting s WHERE s.merchantId = :merchant")
    Optional<CashFlowSetting> findForUpdate(@Param("merchant") Long merchant);

    // 같은 가맹점의 최초 설정 요청이 겹쳐도 한 행만 생성한다.
    @Modifying
    @Query(value = """
            INSERT INTO cash_flow_settings(merchant_id, opening_balance, base_date, created_at, updated_at)
            VALUES (:merchant, :balance, :date, :now, :now)
            ON DUPLICATE KEY UPDATE setting_id = setting_id
            """, nativeQuery = true)
    void ensureInitial(
            @Param("merchant") Long merchantId,
            @Param("balance") Long balance,
            @Param("date") LocalDate date,
            @Param("now") LocalDateTime now);
}
