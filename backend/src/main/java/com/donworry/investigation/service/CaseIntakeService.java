package com.donworry.investigation.service;

import com.donworry.investigation.entity.CaseHistory;
import com.donworry.investigation.entity.CaseHistoryEventType;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.CaseHistoryRepository;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.InvestigationEvents.CaseEvidenceChanged;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// HELD 결제를 받아 Case를 만들거나 30분 안의 Case에 병합한다 (fds 정본 03 §3.4)
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseIntakeService {

    private final SecurityCaseRepository securityCaseRepository;
    private final CasePaymentRepository casePaymentRepository;
    private final CaseHistoryRepository caseHistoryRepository;
    private final CaseStatusService caseStatusService;
    private final ApplicationEventPublisher events;

    // 같은 결제가 다시 와도 이미 연결된 Case를 돌려주고 아무것도 바꾸지 않는다 (AGENTS 4절 멱등)
    @Transactional
    public Long receive(HeldPayment heldPayment) {
        Optional<CasePayment> linked = casePaymentRepository.findByPaymentId(heldPayment.paymentId());
        if (linked.isPresent()) {
            log.info("이미 Case에 연결된 결제 paymentId={}", heldPayment.paymentId());
            return linked.get().getSecurityCase().getId();
        }

        Instant now = Instant.now();
        SecurityCase securityCase = findMergeTarget(heldPayment, now).orElse(null);
        if (securityCase == null) {
            securityCase = securityCaseRepository.save(SecurityCase.open(
                    heldPayment.merchantId(), heldPayment.customerRef(), CaseType.PAYMENT_FRAUD, now));
            caseHistoryRepository.save(CaseHistory.bySystem(
                    securityCase, CaseHistoryEventType.CASE_CREATED, Map.of("caseId", securityCase.getId()), now));
        } else {
            securityCase.increaseEvidenceVersion();
        }

        CasePayment casePayment =
                casePaymentRepository.save(CasePayment.link(securityCase, heldPayment.paymentId(), now));
        caseHistoryRepository.save(CaseHistory.bySystem(
                securityCase,
                CaseHistoryEventType.PAYMENT_LINKED,
                Map.of(
                        "paymentId", heldPayment.paymentId(),
                        "casePaymentId", casePayment.getId(),
                        "evidenceVersion", securityCase.getEvidenceVersion()),
                now));
        caseStatusService.refresh(securityCase, now);
        events.publishEvent(new CaseEvidenceChanged(securityCase.getId(), securityCase.getEvidenceVersion()));
        log.info(
                "HELD 결제 Case 연결 caseId={} paymentId={} evidenceVersion={}",
                securityCase.getId(),
                heldPayment.paymentId(),
                securityCase.getEvidenceVersion());
        return securityCase.getId();
    }

    // 후보는 쓰기 잠금으로 읽는다 — 같은 Case 동시 병합에서 증거 버전 증가가 덮어써지지 않게
    private Optional<SecurityCase> findMergeTarget(HeldPayment heldPayment, Instant now) {
        return securityCaseRepository
                .findMergeCandidates(
                        heldPayment.merchantId(),
                        heldPayment.customerRef(),
                        CaseType.PAYMENT_FRAUD,
                        InvestigationRules.MERGEABLE_STATUSES,
                        now.minus(InvestigationRules.MERGE_WINDOW))
                .stream()
                .filter(candidate ->
                        InvestigationRules.canMerge(candidate.getStatus(), candidate.getCreatedAt(), 0, now))
                .findFirst();
    }
}
