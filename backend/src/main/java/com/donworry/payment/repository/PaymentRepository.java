package com.donworry.payment.repository;

import com.donworry.payment.entity.Payment;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 금융 기록의 물리 삭제를 노출하지 않고 필요한 저장·조회만 제공한다.
public interface PaymentRepository extends Repository<Payment, Long> {

    Payment save(Payment payment);

    @Query("select count(p) from Payment p where p.merchantId = :merchantId and p.orderRef = :orderRef "
            + "and not exists (select r.requestId from PaymentRequest r where r.paymentId = p.paymentId)")
    long countUntrackedByMerchantIdAndOrderRef(
            @Param("merchantId") Long merchantId, @Param("orderRef") String orderRef);

    Optional<Payment> findById(Long paymentId);

    Optional<Payment> findByPaymentIdAndMerchantId(Long paymentId, Long merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.paymentId = :paymentId")
    Optional<Payment> findByIdForUpdate(@Param("paymentId") Long paymentId);
}
