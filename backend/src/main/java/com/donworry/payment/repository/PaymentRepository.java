package com.donworry.payment.repository;

import com.donworry.payment.entity.Payment;
import java.util.Optional;
import org.springframework.data.repository.Repository;

// 금융 기록의 물리 삭제를 노출하지 않고 필요한 저장·조회만 제공한다.
public interface PaymentRepository extends Repository<Payment, Long> {

    Payment save(Payment payment);

    Optional<Payment> findById(Long paymentId);
}
