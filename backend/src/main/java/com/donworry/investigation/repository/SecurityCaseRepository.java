package com.donworry.investigation.repository;

import com.donworry.investigation.entity.CaseStatus;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 조사 기록의 물리 삭제를 노출하지 않고 필요한 저장·조회만 제공한다
public interface SecurityCaseRepository extends Repository<SecurityCase, Long> {

    SecurityCase save(SecurityCase securityCase);

    Optional<SecurityCase> findById(Long caseId);

    // 판정 · 종료처럼 Case 상태를 확인하고 바꾸는 경로는 이 잠금으로 다시 읽는다
    // 병합(findMergeCandidates)과 같은 행 잠금을 쓰므로 판정 중에 결제가 끼어들어 증거 버전이 바뀌지 않는다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM SecurityCase c WHERE c.id = :id")
    Optional<SecurityCase> findByIdForUpdate(@Param("id") Long id);

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

    // 보안 직원 Case 목록 — 최근 생성 순
    List<SecurityCase> findAllByOrderByCreatedAtDesc();
}
