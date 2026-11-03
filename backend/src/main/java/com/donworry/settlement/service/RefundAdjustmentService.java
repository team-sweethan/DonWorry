package com.donworry.settlement.service;

import com.donworry.settlement.api.PaymentRefundSucceeded;
import com.donworry.settlement.entity.*;
import com.donworry.settlement.repository.*;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class RefundAdjustmentService {
    private final SettlementRepository settlements;
    private final CaptureRepository captures;
    private final SettlementRefundAdjustmentRepository adjustments;
    private final SettlementMerchantLockService merchantLocks;
    private final MerchantPayoutItemRepository payoutItems;

    // 가맹점 → 정산 순서로 잠가 재전달과 지급 배치를 같은 순서로 직렬화한다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SettlementRefundAdjustment record(PaymentRefundSucceeded event) {
        if (event == null) throw new IllegalArgumentException("환불 성공 정보가 필요합니다.");
        return merchantLocks.execute(event.merchantId(), () -> recordLocked(event));
    }

    private SettlementRefundAdjustment recordLocked(PaymentRefundSucceeded event) {
        var settlement = settlements
                .findForRefund(event.paymentId())
                .orElseThrow(() -> new IllegalStateException("환불 대상 정산이 없습니다."));
        var existing = adjustments.findByCancellationId(event.cancellationId()).orElse(null);
        if (existing != null) {
            if (!existing.matchesRequest(event)
                    || !Objects.equals(existing.getSettlementId(), settlement.getSettlementId()))
                throw new IllegalStateException("같은 취소 ID의 내용이 기존 수신 기록과 다릅니다.");
            // 이미 적용된 조정도 다시 만들거나 잔여 금액을 초기화하지 않는다.
            excludeUnpaid(settlement, existing, Instant.now());
            return existing;
        }
        if (adjustments.findBySettlementId(settlement.getSettlementId()).isPresent())
            throw new IllegalStateException("이미 전액 환불을 수신한 정산입니다.");
        var capture =
                captures.findById(event.captureId()).orElseThrow(() -> new IllegalStateException("환불 대상 매입이 없습니다."));
        if (capture.getStatus() != CaptureStatus.COMPLETED
                || capture.getCapturedAt() == null
                || !Objects.equals(capture.getCaptureId(), settlement.getCaptureId())
                || !Objects.equals(capture.getPaymentId(), event.paymentId())
                || !Objects.equals(capture.getMerchantId(), event.merchantId())
                || !Objects.equals(capture.getCaptureAmount(), event.cancellationAmount())
                || (settlement.getStatus() != SettlementStatus.SCHEDULED
                        && settlement.getStatus() != SettlementStatus.COMPLETED))
            throw new IllegalStateException("환불 성공 정보와 원 매입·정산 근거가 일치하지 않습니다.");
        var now = Instant.now();
        var adjustment = adjustments.save(SettlementRefundAdjustment.received(event, settlement, now));
        excludeUnpaid(settlement, adjustment, now);
        return adjustment;
    }

    // 지급 서비스가 같은 가맹점 잠금을 먼저 잡은 뒤 호출한다. 이전 버전의 미적용 수신도 빠뜨리지 않는다.
    @Transactional(propagation = Propagation.MANDATORY)
    public void excludePendingForMerchant(Long merchantId) {
        long cursor = 0;
        while (true) {
            var page = adjustments.findPendingUnpaid(merchantId, cursor, PageRequest.of(0, 200));
            if (page.isEmpty()) return;
            for (var adjustment : page) {
                cursor = adjustment.getAdjustmentId();
                var settlement =
                        settlements.findForRefund(adjustment.getPaymentId()).orElseThrow();
                excludeUnpaid(settlement, adjustment, Instant.now());
            }
        }
    }

    private void excludeUnpaid(Settlement settlement, SettlementRefundAdjustment adjustment, Instant now) {
        if (adjustment.getStatus() != RefundAdjustmentStatus.PENDING) return;
        if (settlement.getStatus() == SettlementStatus.COMPLETED) return; // 지급 후 회수는 5단계에서 처리한다.
        if (payoutItems.existsBySettlementId(settlement.getSettlementId()))
            throw new IllegalStateException("지급 상세가 있는 정산을 미지급 환불로 제외할 수 없습니다.");
        adjustment.excludeBeforePayout(settlement, now);
        settlement.excludeForRefund(now);
    }
}
