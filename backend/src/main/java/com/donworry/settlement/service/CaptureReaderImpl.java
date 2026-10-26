package com.donworry.settlement.service;

import com.donworry.settlement.api.CaptureReader;
import com.donworry.settlement.api.CaptureSnapshot;
import com.donworry.settlement.repository.CaptureStatusRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CaptureReaderImpl implements CaptureReader {
    private final CaptureStatusRepository repository;

    // 호출자의 미커밋 변경·트랜잭션 설정과 분리하여 저장된 P6 사실만 조회한다.
    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<CaptureSnapshot> findByPaymentId(Long paymentId) {
        if (paymentId == null || paymentId <= 0) {
            throw new IllegalArgumentException("paymentId는 양수여야 합니다.");
        }
        return repository
                .findStatusByPaymentId(paymentId)
                .map(row -> CaptureSnapshot.builder()
                        .paymentId(row.getPaymentId())
                        .captureId(row.getCaptureId())
                        .captureStatus(row.getCaptureStatus().name())
                        .captureAmount(row.getCaptureAmount())
                        .capturedAt(row.getCapturedAt())
                        .settlementExists(row.getSettlementId() != null)
                        .settlementStatus(
                                row.getSettlementStatus() == null
                                        ? null
                                        : row.getSettlementStatus().name())
                        .build());
    }
}
