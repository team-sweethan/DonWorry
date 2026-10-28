package com.donworry.payment.repository;

import com.donworry.payment.entity.PaymentRequest;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface PaymentRequestRepository extends Repository<PaymentRequest, Long> {
    PaymentRequest save(PaymentRequest request);

    @org.springframework.data.jpa.repository.Query("select new com.donworry.payment.dto.MerchantPaymentResponse("
            + "p.paymentId, p.orderRef, r.attemptNo, p.status, p.createdAt, p.statusChangedAt, p.amount, p.declineCode) "
            + "from PaymentRequest r join Payment p on p.paymentId = r.paymentId "
            + "where r.paymentOrderId = :orderId and r.merchantId = :merchantId and p.merchantId = :merchantId "
            + "and (:beforeAttemptNo is null or r.attemptNo < :beforeAttemptNo) order by r.attemptNo desc")
    java.util.List<com.donworry.payment.dto.MerchantPaymentResponse> findMerchantAttempts(
            @org.springframework.data.repository.query.Param("merchantId") Long merchantId,
            @org.springframework.data.repository.query.Param("orderId") Long orderId,
            @org.springframework.data.repository.query.Param("beforeAttemptNo") Long beforeAttemptNo,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from PaymentRequest r where r.paymentId = :paymentId")
    Optional<PaymentRequest> findByPaymentIdForUpdate(
            @org.springframework.data.repository.query.Param("paymentId") Long paymentId);

    Optional<PaymentRequest> findByMerchantIdAndIdempotencyKey(Long merchantId, java.util.UUID key);

    Optional<PaymentRequest> findByPaymentId(Long paymentId);

    Optional<PaymentRequest> findFirstByPaymentOrderIdOrderByAttemptNoDesc(Long paymentOrderId);

    // 재평가 접수·실행 전용: 쿼리별 5초 대기, 연결의 세션 설정은 바꾸지 않는다.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.QueryHints(
            @jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @org.springframework.data.jpa.repository.Query("select r from PaymentRequest r where r.paymentId = :paymentId")
    Optional<PaymentRequest> findByPaymentIdForReevaluation(
            @org.springframework.data.repository.query.Param("paymentId") Long paymentId);
}
