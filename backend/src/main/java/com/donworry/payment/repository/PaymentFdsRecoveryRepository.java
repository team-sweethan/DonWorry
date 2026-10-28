package com.donworry.payment.repository;

import com.donworry.payment.entity.PaymentFdsRecovery;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentFdsRecoveryRepository extends JpaRepository<PaymentFdsRecovery, Long> {
    Optional<PaymentFdsRecovery> findByPaymentId(Long paymentId);
    // 호출 서비스에서 payments → payment_requests → recovery 순서로 잠근다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaymentFdsRecovery r where r.paymentId = :paymentId")
    Optional<PaymentFdsRecovery> findByPaymentIdForUpdate(@Param("paymentId") Long paymentId);
}
