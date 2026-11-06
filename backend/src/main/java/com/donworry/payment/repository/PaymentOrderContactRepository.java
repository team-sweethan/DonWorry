package com.donworry.payment.repository;

import com.donworry.payment.entity.PaymentOrderContact;
import java.util.Optional;
import org.springframework.data.repository.Repository;

// 주문자 연락처는 세션과 함께 한 번 저장하고 고치지 않는다
public interface PaymentOrderContactRepository extends Repository<PaymentOrderContact, Long> {
    PaymentOrderContact save(PaymentOrderContact contact);

    Optional<PaymentOrderContact> findById(Long sessionId);
}
