package com.donworry.settlement.service;

import com.donworry.settlement.repository.SettlementRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class SettlementCompletionCandidateService {
    private final SettlementRepository settlements;

    // 가맹점별 지급 트랜잭션이 시작되기 전에 조회 연결을 반환해 작은 풀에서도 고갈을 막는다.
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public long upperBound(Instant cutoff) {
        return settlements.completionUpperBound(cutoff);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Long> nextPage(Instant cutoff, long afterMerchant, long upper) {
        return settlements.findCompletionMerchants(cutoff, afterMerchant, upper, PageRequest.of(0, 200));
    }
}
