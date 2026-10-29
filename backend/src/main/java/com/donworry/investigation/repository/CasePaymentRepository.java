package com.donworry.investigation.repository;

import com.donworry.investigation.entity.CasePayment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

// 조사 기록의 물리 삭제를 노출하지 않고 필요한 저장·조회만 제공한다
public interface CasePaymentRepository extends Repository<CasePayment, Long> {

    CasePayment save(CasePayment casePayment);

    Optional<CasePayment> findById(Long casePaymentId);

    List<CasePayment> findBySecurityCaseIdOrderByLinkedAtAsc(Long caseId);

    // P4 비슷한 과거 사건(#296) — 여러 사건의 결제를 한 번에 읽는다 (읽기 전용)
    List<CasePayment> findBySecurityCaseIdIn(Collection<Long> caseIds);

    // 같은 결제가 두 번 들어와도 한 번만 연결하기 위한 확인 (UNIQUE(payment_id)와 이중 방어)
    Optional<CasePayment> findByPaymentId(Long paymentId);
}
