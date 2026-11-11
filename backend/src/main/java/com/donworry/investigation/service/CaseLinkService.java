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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// HELD 결제 한 건을 Case에 연결하는 트랜잭션 — 진입은 CaseIntakeService.receive (fds 정본 03 §3.4)
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseLinkService {

    private final SecurityCaseRepository securityCaseRepository;
    private final CasePaymentRepository casePaymentRepository;
    private final CaseHistoryRepository caseHistoryRepository;
    private final CaseStatusService caseStatusService;
    private final CaseAssignmentService caseAssignmentService;
    private final ApplicationEventPublisher events;

    // 같은 결제가 다시 와도 이미 연결된 Case를 돌려주고 아무것도 바꾸지 않는다 (AGENTS 4절 멱등)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long link(HeldPayment heldPayment) {
        // 잠금 조회를 트랜잭션의 첫 조회로 둔다 — 일반 조회가 먼저면 그 시점 스냅숏이 고정돼
        // 잠금을 기다린 뒤 MariaDB가 "Record has changed since last read"로 실패한다 (innodb_snapshot_isolation)
        // 배정 잠금이 맨 앞이다 (#608): 새 사건인지는 병합 후보를 읽어야 알 수 있고, 배정 건수는 잠금 뒤 스냅숏으로 세야
        // 동시에 생긴 사건이 한 직원에게 몰리지 않는다. 그래서 HELD 결제의 사건 연결은 모두 한 줄로 선다
        caseAssignmentService.lock();
        Instant now = Instant.now();
        SecurityCase securityCase = findMergeTarget(heldPayment, now).orElse(null);

        Optional<CasePayment> linked = casePaymentRepository.findByPaymentId(heldPayment.paymentId());
        if (linked.isPresent()) {
            log.info("이미 Case에 연결된 결제 paymentId={}", heldPayment.paymentId());
            return linked.get().getSecurityCase().getId();
        }

        if (securityCase == null) {
            securityCase = securityCaseRepository.save(SecurityCase.open(
                    heldPayment.merchantId(), heldPayment.customerRef(), CaseType.PAYMENT_FRAUD, now));
            caseHistoryRepository.save(CaseHistory.bySystem(
                    securityCase, CaseHistoryEventType.CASE_CREATED, Map.of("caseId", securityCase.getId()), now));
            // 새 사건만 담당을 정한다. 병합된 사건은 처음 담당이 그대로 맡는다
            caseAssignmentService.assignNew(securityCase, now);
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
