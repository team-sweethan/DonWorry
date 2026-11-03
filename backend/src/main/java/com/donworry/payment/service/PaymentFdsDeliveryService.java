package com.donworry.payment.service;

import com.donworry.fds.api.*;
import com.donworry.payment.dto.PaymentFdsRecoveryResponse;
import com.donworry.payment.listener.FdsEvaluationFailureAnalyzedListener;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.*;
import org.springframework.modulith.events.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentFdsDeliveryService {
    private static final String LISTENER = FdsEvaluationFailureAnalyzedListener.class.getName() + ".on("
            + FdsEvaluationFailureAnalyzed.class.getName() + ")";
    private final PaymentFdsStaffAccessService access;
    private final PaymentFdsDeliveryGuard guard;
    private final PaymentFdsDeliverySnapshotService snapshots;
    private final FdsFailureReporter reporter;
    private final EventPublicationRegistry registry;
    private final IncompleteEventPublications publications;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentFdsRecoveryResponse recover(Long paymentId) {
        long staffId = access.requireStaff();
        if (paymentId == null || paymentId <= 0) throw new IllegalArgumentException("결제 ID는 양수여야 합니다.");
        try {
            var result = guard.execute(() -> deliver(paymentId));
            log.info("FDS 전달 복구 staffId={} paymentId={} status={}", staffId, paymentId, result.status());
            return result;
        } catch (RuntimeException exception) {
            log.warn(
                    "FDS 전달 복구 실패 staffId={} paymentId={} exceptionType={}",
                    staffId,
                    paymentId,
                    exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private PaymentFdsRecoveryResponse deliver(Long paymentId) {
        var original = snapshots.read(paymentId);
        if (original.report() == null) return original.summary();
        var report = original.report();
        // 트랜잭션 없이 P2에 진입한다. 기존 보고 ID는 이벤트 재발행을 유발하지 않는다.
        reporter.report(report);
        var afterReport = snapshots.read(paymentId);
        if (afterReport.report() == null) return afterReport.summary();
        var matches = registry.findIncompletePublications().stream()
                .filter(p -> sameTarget(p, report))
                .toList();
        boolean unavailable = matches.size() != 1;
        if (!unavailable) {
            var candidate = matches.getFirst();
            var status = candidate.getStatus();
            unavailable = candidate.getCompletionDate().isPresent()
                    || status == null
                    || status == EventPublication.Status.COMPLETED;
            if (!unavailable && status == EventPublication.Status.FAILED) {
                UUID id = candidate.getIdentifier();
                publications.resubmitIncompletePublications(
                        p -> p.getIdentifier().equals(id)
                                && sameTarget(p, report)
                                && p.getStatus() == EventPublication.Status.FAILED
                                && p.getCompletionDate().isEmpty());
            }
            // PUBLISHED/PROCESSING/RESUBMITTED는 중단이라고 추정하지 않고 그대로 둔다.
        }
        var latest = snapshots.read(paymentId);
        if (latest.report() != null && unavailable)
            throw new PaymentFdsDeliveryNotAllowedException(
                    PaymentFdsDeliveryNotAllowedException.Reason.DELIVERY_RECORD_UNAVAILABLE);
        return latest.summary();
    }

    private boolean sameTarget(EventPublication publication, FdsEvaluationFailureReport report) {
        return publication instanceof TargetEventPublication target
                && target.getTargetIdentifier().getValue().equals(LISTENER)
                && publication.getEvent() instanceof FdsEvaluationFailureAnalyzed event
                && report.paymentId().equals(event.paymentId())
                && report.failureReportId().equals(event.failureReportId())
                && report.originalEvaluationRequestId().equals(event.originalEvaluationRequestId())
                && report.evaluationRequestId().equals(event.evaluationRequestId());
    }
}
