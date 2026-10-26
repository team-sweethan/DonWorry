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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 금융 조치 인계 요청 내용 조회와 P5 결과 반영 (fds 정본 03 §3.8, ADR-0027 완료 기반)
// 결과는 응답 · 결과 이벤트 · 다시 확인 어느 길로 와도 멱등키로 찾아 한 번만 반영하고, 완료 상태를 되돌리지 않는다
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
                .map(HandoffService::command);
    }

    // 다시 확인 대상 — 이 Case의 인계이고 아직 대기 중(REQUESTED)인 것만
    @Transactional(readOnly = true)
    public Command recheckCommandFor(Long caseId, Long handoffId) {
        ActionHandoff handoff = actionHandoffRepository
                .findById(handoffId)
                .filter(found -> caseIdOf(found).equals(caseId))
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.HANDOFF_NOT_FOUND));
        if (handoff.getStatus() != HandoffStatus.REQUESTED) {
            throw new InvestigationException(InvestigationErrorCode.HANDOFF_NOT_PENDING);
        }
        return command(handoff);
    }

    // 판정 직후 P5 응답을 반영한다. 아직 처리 중(PENDING)이면 REQUESTED로 두고 실패로 단정하지 않는다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyReceipt(UUID idempotencyKey, Receipt receipt) {
        if (!receipt.isCompleted()) {
            return;
        }
        ActionHandoff handoff = lockedHandoff(idempotencyKey);
        apply(handoff, receipt, Map.of(), Instant.now());
    }

    // P5 결과 이벤트를 반영한다 — 리스너 트랜잭션에 합류한다
    // 인계가 없거나 결제 · 판정 · 조치 유형이 다르면 반영하지 않고 예외로 남긴다(미완료 발행으로 P5와 대조)
    @Transactional
    public void applyResult(Command result, Receipt receipt, Instant completedAt) {
        ActionHandoff handoff = actionHandoffRepository
                .findByIdempotencyKey(result.idempotencyKey())
                .orElseThrow(() -> mismatch("인계가 없습니다", result));
        if (!command(handoff).equals(result)) {
            throw mismatch("인계와 결과의 결제 · 판정 · 조치 유형이 다릅니다", result);
        }
        // 실제 완료 시각은 이력에만 남긴다. acceptedAt은 P3 반영 시각이다
        apply(handoff, receipt, Map.of("completedAt", completedAt.toString()), Instant.now());
    }

    // 직원의 다시 확인 결과를 반영한다. 계속 처리 중이면 확인 시각만 남긴다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyRecheck(UUID idempotencyKey, Receipt receipt, Long staffId) {
        ActionHandoff handoff = lockedHandoff(idempotencyKey);
        Instant now = Instant.now();
        if (handoff.getStatus() == HandoffStatus.REQUESTED) {
            handoff.retry(now);
        }
        caseHistoryRepository.save(CaseHistory.byStaff(
                lockedCase(handoff),
                CaseHistoryEventType.HANDOFF_RECHECKED,
                staffId,
                Map.of("handoffId", handoff.getId(), "result", receipt.status().name()),
                now));
        if (receipt.isCompleted()) {
            apply(handoff, receipt, Map.of("source", "RECHECK"), now);
        }
    }

    private void apply(ActionHandoff handoff, Receipt receipt, Map<String, Object> extra, Instant now) {
        SecurityCase securityCase = lockedCase(handoff);
        HandoffStatus before = handoff.getStatus();

        if (before == HandoffStatus.ACCEPTED && !isSameCompletion(handoff, receipt)) {
            throw mismatch("이미 완료된 인계와 다른 결과입니다", command(handoff));
        }
        if (receipt.status() == Receipt.Status.ACCEPTED) {
            handoff.accept(receipt.targetOperationId(), now);
            if (before != HandoffStatus.ACCEPTED) {
                caseHistoryRepository.save(CaseHistory.bySystem(
                        securityCase,
                        CaseHistoryEventType.HANDOFF_ACCEPTED,
                        eventData(handoff, "targetOperationId", receipt.targetOperationId(), extra),
                        now));
            }
        } else if (before == HandoffStatus.REQUESTED) {
            handoff.fail(receipt.errorCode());
            caseHistoryRepository.save(CaseHistory.bySystem(
                    securityCase,
                    CaseHistoryEventType.HANDOFF_FAILED,
                    eventData(handoff, "errorCode", receipt.errorCode(), extra),
                    now));
        }
        if (!securityCase.isClosed()) {
            caseStatusService.refresh(securityCase, now);
        }
    }

    // 쓰기 잠금 — 결과 이벤트와 다시 확인이 같은 행을 동시에 바꾸지 않게 한다
    private ActionHandoff lockedHandoff(UUID idempotencyKey) {
        return actionHandoffRepository
                .findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalArgumentException("인계가 없습니다 idempotencyKey=" + idempotencyKey));
    }

    private SecurityCase lockedCase(ActionHandoff handoff) {
        return securityCaseRepository.findByIdForUpdate(caseIdOf(handoff)).orElseThrow();
    }

    private static Map<String, Object> eventData(
            ActionHandoff handoff, String key, Object value, Map<String, Object> extra) {
        Map<String, Object> data = new HashMap<>(extra);
        data.put("handoffId", handoff.getId());
        data.put(key, Objects.requireNonNull(value, key));
        return data;
    }

    // 완료된 인계에 같은 결과가 다시 오면 넘긴다. 다른 작업 ID · 거절이 오면 충돌이다
    private static boolean isSameCompletion(ActionHandoff handoff, Receipt receipt) {
        return receipt.status() == Receipt.Status.ACCEPTED
                && Objects.equals(receipt.targetOperationId(), handoff.getTargetOperationId());
    }

    private static Command command(ActionHandoff handoff) {
        return new Command(
                handoff.getDecision().getCasePayment().getPaymentId(),
                handoff.getDecision().getId(),
                handoff.getActionType(),
                handoff.getIdempotencyKey());
    }

    private static Long caseIdOf(ActionHandoff handoff) {
        return handoff.getDecision().getCasePayment().getSecurityCase().getId();
    }

    private static IllegalStateException mismatch(String message, Command result) {
        log.error(
                "금융 조치 결과 반영 거부 — {} paymentId={} decisionId={} actionType={} idempotencyKey={}",
                message,
                result.paymentId(),
                result.decisionId(),
                result.actionType(),
                result.idempotencyKey());
        return new IllegalStateException(message + " idempotencyKey=" + result.idempotencyKey());
    }
}
