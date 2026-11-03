package com.donworry.settlement.service;

import com.donworry.settlement.api.PaymentRefundSucceeded;
import com.donworry.settlement.entity.*;
import com.donworry.settlement.repository.*;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class RefundAdjustmentService {
    private final SettlementRepository settlements;
    private final CaptureRepository captures;
    private final SettlementRefundAdjustmentRepository adjustments;

    // 같은 결제의 정산 행을 잠가 동시 재전달과 서로 다른 취소 ID의 전액 중복을 직렬화한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public SettlementRefundAdjustment record(PaymentRefundSucceeded event) {
        if (event == null) throw new IllegalArgumentException("환불 성공 정보가 필요합니다.");
        var settlement = settlements
                .findForRefund(event.paymentId())
                .orElseThrow(() -> new IllegalStateException("환불 대상 정산이 없습니다."));
        var existing = adjustments.findByCancellationId(event.cancellationId()).orElse(null);
        if (existing != null) {
            if (!existing.matchesRequest(event)
                    || !Objects.equals(existing.getSettlementId(), settlement.getSettlementId()))
                throw new IllegalStateException("같은 취소 ID의 내용이 기존 수신 기록과 다릅니다.");
            // 이미 적용된 조정도 다시 만들거나 잔여 금액을 초기화하지 않는다.
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
        return adjustments.save(SettlementRefundAdjustment.received(event, settlement, Instant.now()));
    }
}
