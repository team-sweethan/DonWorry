package com.donworry.investigation.repository;

import com.donworry.investigation.entity.CasePayment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CasePaymentRepository extends JpaRepository<CasePayment, Long> {

    List<CasePayment> findBySecurityCaseIdOrderByLinkedAtAsc(Long caseId);

    // 같은 결제가 두 번 들어와도 한 번만 연결하기 위한 확인 (UNIQUE(payment_id)와 이중 방어)
    Optional<CasePayment> findByPaymentId(Long paymentId);
}
