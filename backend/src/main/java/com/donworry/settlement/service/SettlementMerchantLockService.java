package com.donworry.settlement.service;

import com.donworry.settlement.repository.MerchantSettlementLockRepository;
import java.time.Instant;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class SettlementMerchantLockService {
    private final MerchantSettlementLockRepository locks;
    private final PlatformTransactionManager transactions;

    // 최초 UPSERT의 인덱스 잠금을 금융 처리까지 보유하지 않도록 준비와 처리를 순차 실행한다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public <T> T execute(Long merchantId, Supplier<T> work) {
        if (merchantId == null || merchantId <= 0 || work == null)
            throw new IllegalArgumentException("가맹점 ID와 처리 작업이 필요합니다.");
        var tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(status -> locks.ensurePresent(merchantId, Instant.now()));
        // 두 트랜잭션은 연결을 반환한 뒤 순차 실행하므로 풀 1개에서도 연결을 이중 점유하지 않는다.
        return tx.execute(status -> {
            locks.findForUpdate(merchantId).orElseThrow(() -> new IllegalStateException("가맹점 정산 잠금이 없습니다."));
            return work.get();
        });
    }
}
