package com.donworry.payment.repository;

import com.donworry.payment.entity.Payment;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
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

    // 고객 취소도 직원 조치와 같은 결제 잠금을 먼저 잡는다. 타 가맹점 결제는 잠그지 않는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.QueryHints(
            @jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select p from Payment p where p.paymentId = :paymentId and p.merchantId = :merchantId")
    Optional<Payment> findOwnedForCancellation(
            @Param("paymentId") Long paymentId, @Param("merchantId") Long merchantId);

    Optional<Payment> findByPaymentIdAndMerchantId(Long paymentId, Long merchantId);

    // 고객별 최근 결제 (#338) — 뺄 결제를 먼저 빼고 limit을 센다. 같은 시각이면 나중에 저장된 결제가 먼저
    @Query("select p from Payment p where p.merchantId = :merchantId and p.customerRef = :customerRef "
            + "and p.createdAt >= :since and p.paymentId not in :excluded "
            + "order by p.createdAt desc, p.paymentId desc")
    List<Payment> findRecentByCustomer(
            @Param("merchantId") Long merchantId,
            @Param("customerRef") String customerRef,
            @Param("since") Instant since,
            @Param("excluded") Collection<Long> excluded,
            Limit limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.paymentId = :paymentId")
    Optional<Payment> findByIdForUpdate(@Param("paymentId") Long paymentId);

    // 재평가 접수·실행 전용: 쿼리별 5초 대기, 연결의 세션 설정은 바꾸지 않는다.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.QueryHints(
            @jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @org.springframework.data.jpa.repository.Query("select r from Payment r where r.paymentId = :paymentId")
    Optional<Payment> findByIdForReevaluation(
            @org.springframework.data.repository.query.Param("paymentId") Long paymentId);
}
