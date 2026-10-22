package com.donworry.investigation.repository;

import com.donworry.investigation.entity.PaymentDecision;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentDecisionRepository extends JpaRepository<PaymentDecision, Long> {

    List<PaymentDecision> findByCasePaymentSecurityCaseId(Long caseId);

    long countByCasePaymentSecurityCaseId(Long caseId);
}
