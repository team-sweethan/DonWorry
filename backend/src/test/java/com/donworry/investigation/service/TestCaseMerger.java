package com.donworry.investigation.service;

import com.donworry.investigation.entity.CaseHistory;
import com.donworry.investigation.entity.CaseHistoryEventType;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.repository.CaseHistoryRepository;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.InvestigationEvents.CaseEvidenceChanged;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 테스트 전용 — 기존 Case에 결제를 하나 더 붙인다 (#428)
// 결제 Case는 이제 결제 1건 = Case 1개라 운영 흐름에서는 결제 여러 건 Case가 생기지 않는다.
// 그래도 판정 · 증거 버전 충돌 · 목록 합계 같은 여러 결제 로직(이후 로그인 보안 경보 Case가 쓸 것)을 계속 검사하려고,
// 예전 병합(CaseLinkService)과 같은 순서로 증거 버전을 올리고 이력·상태·보고서 요청을 남긴다
@RequiredArgsConstructor
public class TestCaseMerger {

    private final SecurityCaseRepository securityCases;
    private final CasePaymentRepository casePayments;
    private final CaseHistoryRepository histories;
    private final CaseStatusService caseStatusService;
    private final ApplicationEventPublisher events;
    private final TestPaymentInfoStore payments;
    private final PlatformTransactionManager transactionManager;

    // 커밋 뒤 보고서 요청이 돌도록 자체 트랜잭션에서 붙이고 결제 ID를 돌려준다
    public Long attach(Long caseId, long amount, int riskScore) {
        Instant now = Instant.now();
        Long paymentId = payments.registerHeld(amount, riskScore, TestPaymentInfoStore.DEFAULT_RULES, now)
                .paymentId();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var securityCase = securityCases.findByIdForUpdate(caseId).orElseThrow();
            securityCase.increaseEvidenceVersion();
            var casePayment = casePayments.save(CasePayment.link(securityCase, paymentId, now));
            histories.save(CaseHistory.bySystem(
                    securityCase,
                    CaseHistoryEventType.PAYMENT_LINKED,
                    Map.of(
                            "paymentId", paymentId,
                            "casePaymentId", casePayment.getId(),
                            "evidenceVersion", securityCase.getEvidenceVersion()),
                    now));
            caseStatusService.refresh(securityCase, now);
            events.publishEvent(new CaseEvidenceChanged(securityCase.getId(), securityCase.getEvidenceVersion()));
        });
        return Objects.requireNonNull(paymentId);
    }
}
