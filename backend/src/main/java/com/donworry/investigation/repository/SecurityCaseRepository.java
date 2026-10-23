package com.donworry.investigation.repository;

import com.donworry.investigation.entity.CaseStatus;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SecurityCaseRepository extends JpaRepository<SecurityCase, Long> {

    // 병합 후보 — 같은 가맹점 · 고객 · 유형, 병합 기한 안, 판정이 하나도 없는 Case (fds 정본 03 §3.4)
    // 조건 순서는 idx_security_cases_merge 컬럼 순서와 같다. 가장 최근 Case가 먼저 온다
    // 쓰기 잠금 — 같은 Case에 동시에 병합될 때 증거 버전 증가가 덮어써지지 않게 차례로 처리한다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT c FROM SecurityCase c
            WHERE c.merchantId = :merchantId
              AND c.customerRef = :customerRef
              AND c.caseType = :caseType
              AND c.status IN :statuses
              AND c.createdAt > :createdAfter
              AND NOT EXISTS (
                  SELECT d FROM PaymentDecision d
                  WHERE d.casePayment.securityCase = c)
            ORDER BY c.createdAt DESC
            """)
    List<SecurityCase> findMergeCandidates(
            @Param("merchantId") Long merchantId,
            @Param("customerRef") String customerRef,
            @Param("caseType") CaseType caseType,
            @Param("statuses") Collection<CaseStatus> statuses,
            @Param("createdAfter") Instant createdAfter);

    List<SecurityCase> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);
}
