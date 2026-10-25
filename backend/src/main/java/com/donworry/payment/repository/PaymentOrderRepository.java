package com.donworry.payment.repository;

import com.donworry.payment.entity.PaymentOrder;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface PaymentOrderRepository extends Repository<PaymentOrder, Long> {
    PaymentOrder save(PaymentOrder order);

    Optional<PaymentOrder> findById(Long id);

    Optional<PaymentOrder> findByMerchantIdAndOrderRef(Long merchantId, String orderRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PaymentOrder o where o.paymentOrderId = :id")
    Optional<PaymentOrder> findByIdForUpdate(@Param("id") Long id);
}
