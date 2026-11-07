package com.donworry.payment.repository;

import com.donworry.payment.entity.PaymentSession;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface PaymentSessionRepository extends Repository<PaymentSession, Long> {
    PaymentSession save(PaymentSession session);

    Optional<PaymentSession> findById(Long sessionId);

    Optional<PaymentSession> findBySessionKey(String sessionKey);

    Optional<PaymentSession> findByPaymentId(Long paymentId);

    // AI 조사 주문 요약 (#634) — 사건 결제 몇 건을 한 번에 읽는다
    List<PaymentSession> findByPaymentIdIn(Collection<Long> paymentIds);

    // 결제 ID가 아직 연결되지 않은 세션 — 결제창은 접수 · 승인 뒤에 세션에 결제 ID를 저장한다 (#634 리뷰)
    Optional<PaymentSession> findByMerchantIdAndAttemptKey(Long merchantId, UUID attemptKey);

    Optional<PaymentSession> findByMerchantIdAndRequestKey(Long merchantId, UUID requestKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PaymentSession s where s.sessionKey = :sessionKey")
    Optional<PaymentSession> findBySessionKeyForUpdate(@Param("sessionKey") String sessionKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PaymentSession s where s.sessionId = :sessionId")
    Optional<PaymentSession> findByIdForUpdate(@Param("sessionId") Long sessionId);

    // 알림 예약 시각이 지난 세션
    @Query("select s.sessionId from PaymentSession s where s.nextNotifyAt is not null and s.nextNotifyAt <= :now "
            + "order by s.nextNotifyAt")
    List<Long> findDueNotifications(@Param("now") Instant now, Pageable pageable);

    // 알림 주소가 있고 결제와 연결된 세션 중 마지막 알림과 결제 상태가 다른 세션
    @Query("select s.sessionId from PaymentSession s join Payment p on p.paymentId = s.paymentId "
            + "where s.notifyUrl is not null and s.nextNotifyAt is null and (s.notifiedStatus is null "
            + "or s.notifiedStatus <> cast(p.status as string) "
            + "or s.notifiedAuthorized <> (case when p.authorizationRef is not null then true else false end))")
    List<Long> findChangedSinceNotified(Pageable pageable);
}
