package com.donworry.fds.repository;

import com.donworry.fds.api.FdsResult;
import com.donworry.fds.entity.FdsEvaluation;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface FdsEvaluationRepository
        extends JpaRepository<FdsEvaluation, Long>, JpaSpecificationExecutor<FdsEvaluation> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<FdsEvaluation> findByMerchantIdAndCustomerRefAndPaymentRequestedAtBetween(
            Long merchantId, String customerRef, Instant windowStart, Instant windowEnd);

    List<FdsEvaluation> findByMerchantIdAndCustomerRef(Long merchantId, String customerRef);

    Optional<FdsEvaluation> findByEvaluationRequestId(UUID evaluationRequestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select evaluation from FdsEvaluation evaluation where evaluation.evaluationRequestId = :evaluationRequestId")
    Optional<FdsEvaluation> findByEvaluationRequestIdForUpdate(UUID evaluationRequestId);

    Optional<FdsEvaluation> findByReevaluationRetryId(UUID reevaluationRetryId);

    long countByResult(FdsResult result);

    @EntityGraph(attributePaths = "ruleHits")
    List<FdsEvaluation> findByEvaluatedAtGreaterThanEqual(Instant evaluatedAt);

    List<FdsEvaluation> findByPaymentIdOrderByEvaluatedAtDesc(Long paymentId);

    @EntityGraph(attributePaths = "ruleHits")
    List<FdsEvaluation> findByPaymentIdInOrderByPaymentIdAscEvaluatedAtDesc(Collection<Long> paymentIds);

    // 같은 가맹점 · 기간 안에서 같은 카드 · 기기 · IP를 쓴 평가 (#352) — 같은 고객 · 사건 결제 제외는 조회 서비스가 한다
    List<FdsEvaluation> findByMerchantIdAndCardTokenInAndPaymentRequestedAtGreaterThanEqual(
            Long merchantId, Collection<String> cardTokens, Instant since);

    List<FdsEvaluation> findByMerchantIdAndDeviceIdInAndPaymentRequestedAtGreaterThanEqual(
            Long merchantId, Collection<String> deviceIds, Instant since);

    List<FdsEvaluation> findByMerchantIdAndIpAddressInAndPaymentRequestedAtGreaterThanEqual(
            Long merchantId, Collection<String> ipAddresses, Instant since);
}
