package com.donworry.payment.repository;

import com.donworry.payment.entity.PaymentOrderContact;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

// 주문자 연락처는 세션과 함께 한 번 저장하고 고치지 않는다
public interface PaymentOrderContactRepository extends Repository<PaymentOrderContact, Long> {
    PaymentOrderContact save(PaymentOrderContact contact);

    Optional<PaymentOrderContact> findById(Long sessionId);

    // AI 조사 주문 요약 (#634) — 사건 결제의 세션 몇 건을 한 번에 읽는다
    List<PaymentOrderContact> findBySessionIdIn(Collection<Long> sessionIds);
}
