package com.donworry.settlement.service;

import com.donworry.settlement.dto.SettlementBatchResult;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementCompletionBatchService {
    private final SettlementCompletionCandidateService candidates;
    private final SettlementCompletionTransactionService transactions;

    // 한 건의 롤백이 이미 확정한 다른 정산을 되돌리지 않도록 건별 트랜잭션으로 처리한다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SettlementBatchResult run(Instant cutoff) {
        if (cutoff == null || cutoff.isAfter(Instant.now()))
            throw new IllegalArgumentException("미래 시각으로 정산을 확정할 수 없습니다.");
        long upper = candidates.upperBound(cutoff), cursor = 0;
        long scanned = 0, completed = 0, skipped = 0, failed = 0;
        while (cursor < upper) {
            var page = candidates.nextPage(cutoff, cursor, upper);
            if (page.isEmpty()) break;
            for (var candidate : page) {
                cursor = candidate.settlementId();
                scanned++;
                try {
                    var result = transactions.complete(
                            candidate.settlementId(), candidate.merchantId(), candidate.orderId(), cutoff);
                    if ("COMPLETED".equals(result)) completed++;
                    else {
                        skipped++;
                        log.info("settlement completion skipped: settlementId={}, reason={}", cursor, result);
                    }
                } catch (RuntimeException ex) {
                    failed++;
                    log.error("settlement completion failed: settlementId={}", cursor, ex);
                }
            }
        }
        return SettlementBatchResult.builder()
                .cutoff(cutoff)
                .scanned(scanned)
                .completed(completed)
                .skipped(skipped)
                .failed(failed)
                .build();
    }
}
