package com.donworry.settlement.repository;

import com.donworry.settlement.entity.OrderFulfillment;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface OrderFulfillmentRepository extends JpaRepository<OrderFulfillment, Long> {
    // UNIQUE 충돌을 같은 주문 행의 잠금으로 사용하여 여러 프로세스에서도 직렬화한다.
    @Modifying
    @Query(value = """
            INSERT INTO order_fulfillments(merchant_id, order_id, created_at, updated_at)
            VALUES (:merchantId, :orderId, :now, :now)
            ON DUPLICATE KEY UPDATE order_id = order_id
            """, nativeQuery = true)
    void ensureOrder(
            @Param("merchantId") Long merchantId, @Param("orderId") String orderId, @Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM OrderFulfillment f WHERE f.merchantId = :merchantId AND f.orderId = :orderId")
    Optional<OrderFulfillment> findForUpdate(@Param("merchantId") Long merchantId, @Param("orderId") String orderId);

    Optional<OrderFulfillment> findByMerchantIdAndOrderId(Long merchantId, String orderId);
}
