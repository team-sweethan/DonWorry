package com.donworry.investigation.repository;

import com.donworry.investigation.entity.Decision;
import com.donworry.investigation.entity.PaymentDecision;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 조사 기록의 물리 삭제를 노출하지 않고 필요한 저장·조회만 제공한다
public interface PaymentDecisionRepository extends Repository<PaymentDecision, Long> {

    PaymentDecision save(PaymentDecision decision);

    Optional<PaymentDecision> findById(Long decisionId);

    List<PaymentDecision> findByCasePaymentSecurityCaseId(Long caseId);

    // P4 비슷한 과거 사건(#296) — 여러 사건의 결제별 판정을 한 번에 읽는다 (읽기 전용)
    List<PaymentDecision> findByCasePaymentSecurityCaseIdIn(Collection<Long> caseIds);

    long countByCasePaymentSecurityCaseId(Long caseId);

    // 목록 화면 — 여러 Case의 판정 건수를 한 번에 센다. 판정이 없는 Case는 결과에 없다
    @Query("""
            SELECT new com.donworry.investigation.repository.CaseDecisionCount(cp.securityCase.id, COUNT(d))
            FROM PaymentDecision d JOIN d.casePayment cp
            WHERE cp.securityCase.id IN :caseIds
            GROUP BY cp.securityCase.id
            """)
    List<CaseDecisionCount> countByCaseIdIn(@Param("caseIds") Collection<Long> caseIds);

    // 고객사 조치 결과 요약 지표 — 가맹점의 ACCEPT · REJECT 판정 건수
    long countByCasePaymentSecurityCaseMerchantIdAndDecision(Long merchantId, Decision decision);
}
