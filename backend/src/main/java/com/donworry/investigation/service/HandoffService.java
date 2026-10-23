package com.donworry.investigation.service;

import com.donworry.investigation.entity.ActionHandoff;
import com.donworry.investigation.entity.CaseHistory;
import com.donworry.investigation.entity.CaseHistoryEventType;
import com.donworry.investigation.entity.HandoffStatus;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.ActionHandoffRepository;
import com.donworry.investigation.repository.CaseHistoryRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.FinancialActionGateway.Command;
import com.donworry.investigation.service.FinancialActionGateway.Receipt;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 금융 조치 인계 요청 내용 조회와 접수 결과 반영 (fds 정본 03 §3.8)
// 둘 다 커밋 뒤 리스너 · P5 결과 이벤트에서 불리므로 새 트랜잭션을 연다
@Slf4j
@Service
@RequiredArgsConstructor
public class HandoffService {

    private final ActionHandoffRepository actionHandoffRepository;
    private final SecurityCaseRepository securityCaseRepository;
    private final CaseHistoryRepository caseHistoryRepository;
    private final CaseStatusService caseStatusService;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<Command> commandFor(Long handoffId) {
        return actionHandoffRepository
                .findById(handoffId)
                .filter(handoff -> handoff.getStatus() == HandoffStatus.REQUESTED)
                .map(handoff -> new Command(
                        handoff.getDecision().getCasePayment().getPaymentId(),
                        handoff.getDecision().getId(),
                        handoff.getActionType(),
                        handoff.getIdempotencyKey()));
    }

    // P5 접수 결과를 멱등키로 찾아 반영한다. 같은 결과가 다시 와도 한 번만 반영된다
    // 응답을 모르면(UNKNOWN) REQUESTED로 두고 실패로 단정하지 않는다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyReceipt(UUID idempotencyKey, Receipt receipt) {
        if (receipt.status() == Receipt.Status.UNKNOWN) {
            log.warn("금융 조치 접수 여부 미확인 idempotencyKey={}", idempotencyKey);
            return;
        }
        ActionHandoff handoff = actionHandoffRepository
                .findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalArgumentException("인계가 없습니다 idempotencyKey=" + idempotencyKey));
        Long caseId = handoff.getDecision().getCasePayment().getSecurityCase().getId();
        SecurityCase securityCase =
                securityCaseRepository.findByIdForUpdate(caseId).orElseThrow();
        HandoffStatus before = handoff.getStatus();
        Instant now = Instant.now();

        if (receipt.status() == Receipt.Status.ACCEPTED) {
            handoff.accept(receipt.targetOperationId(), now);
            if (before != HandoffStatus.ACCEPTED) {
                caseHistoryRepository.save(CaseHistory.bySystem(
                        securityCase,
                        CaseHistoryEventType.HANDOFF_ACCEPTED,
                        Map.of("handoffId", handoff.getId(), "targetOperationId", receipt.targetOperationId()),
                        now));
            }
        } else if (before == HandoffStatus.REQUESTED) {
            handoff.fail(receipt.errorCode());
            caseHistoryRepository.save(CaseHistory.bySystem(
                    securityCase,
                    CaseHistoryEventType.HANDOFF_FAILED,
                    Map.of("handoffId", handoff.getId(), "errorCode", receipt.errorCode()),
                    now));
        }
        if (!securityCase.isClosed()) {
            caseStatusService.refresh(securityCase, now);
        }
    }
}
