package com.donworry.settlement.service;

import com.donworry.settlement.entity.*;
import com.donworry.settlement.repository.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class SettlementCompletionTransactionService {
    private final SettlementRepository settlements;
    private final CaptureRepository captures;
    private final SettlementCompletionPolicy policy;
    private final MerchantPayoutRepository payouts;
    private final MerchantPayoutItemRepository payoutItems;
    private final SettlementMerchantLockService merchantLocks;
    private final RefundAdjustmentService refunds;

    // 날짜와 무관한 가맹점 잠금을 먼저 잡아 환불과 여러 서버의 지급을 함께 직렬화한다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String completeMerchant(Long merchantId, Instant cutoff, long upperSettlementId) {
        var started = Instant.now();
        if (merchantId == null
                || merchantId <= 0
                || cutoff == null
                || cutoff.isAfter(started)
                || upperSettlementId <= 0) throw new IllegalArgumentException("가맹점과 현재 이하 기준 시각·후보 상한이 필요합니다.");
        return merchantLocks.execute(merchantId, () -> completeLocked(merchantId, cutoff, upperSettlementId));
    }

    private String completeLocked(Long merchantId, Instant cutoff, long upperSettlementId) {
        var started = Instant.now();
        var date = started.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        refunds.excludePendingForMerchant(merchantId);
        if (payouts.existsByMerchantIdAndPayoutDateAndStatus(merchantId, date, PayoutStatus.COMPLETED))
            return "ALREADY_COMPLETED";
        if (settlements.countForPayout(merchantId, cutoff, upperSettlementId) == 0) return "EMPTY";
        payouts.ensurePending(merchantId, date, LocalDateTime.ofInstant(started, ZoneOffset.UTC));
        var payout = payouts.findForUpdate(merchantId, date).orElseThrow();
        if (payout.getStatus() == PayoutStatus.COMPLETED) return "ALREADY_COMPLETED";
        // 넓은 FOR UPDATE 스캔은 다른 가맹점 행에서도 대기할 수 있어 ID 조회 뒤 PK로만 잠근다.
        var targets = settlements.findPayoutTargetIds(merchantId, cutoff, upperSettlementId).stream()
                .map(id -> settlements.findForCompletion(id).orElseThrow())
                .toList();
        if (targets.isEmpty()) throw new IllegalStateException("잠금 후 지급 대상이 없어 지급을 확정하지 않습니다.");
        var source = captures
                .findAllById(targets.stream().map(Settlement::getCaptureId).toList())
                .stream()
                .collect(Collectors.toMap(Capture::getCaptureId, c -> c));
        var completedAt = Instant.now();
        long total = 0;
        var details = new ArrayList<MerchantPayoutItem>();
        for (var settlement : targets) {
            var reason = policy.evaluate(settlement, source.get(settlement.getCaptureId()), cutoff);
            if (!"ELIGIBLE".equals(reason))
                throw new IllegalStateException("지급 대상 정합성 오류: " + settlement.getSettlementId() + " / " + reason);
            total = Math.addExact(total, settlement.getSettlementAmount());
            details.add(MerchantPayoutItem.snapshot(
                    payout.getPayoutId(), settlement.getSettlementId(), settlement.getSettlementAmount(), completedAt));
            settlement.complete(completedAt);
        }
        payoutItems.saveAll(details);
        // 완료일이 자정을 넘었다면 전체 롤백해 지급일과 실제 완료일을 다르게 기록하지 않는다.
        payout.complete(total, completedAt);
        return "COMPLETED";
    }
}
