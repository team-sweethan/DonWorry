package com.donworry.settlement.service;

import com.donworry.identity.api.TenantReader;
import com.donworry.settlement.entity.Capture;
import com.donworry.settlement.entity.CaptureStatus;
import com.donworry.settlement.entity.Settlement;
import com.donworry.settlement.repository.SettlementRepository;
import com.donworry.settlement.service.SettlementFeeCalculator.FeeResult;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final SettlementFeeCalculator feeCalculator;
    private final TenantReader tenants;

    @Transactional
    public Settlement createSettlement(Capture capture) {

        if (capture == null || capture.getStatus() != CaptureStatus.COMPLETED) {
            throw new IllegalArgumentException("매입 완료 건만 정산할 수 있습니다.");
        }

        if (capture.getCaptureId() == null) {
            throw new IllegalArgumentException("저장되지 않은 매입 건은 정산할 수 없습니다.");
        }

        Settlement existingSettlement =
                settlementRepository.findByCaptureId(capture.getCaptureId()).orElse(null);

        if (existingSettlement != null) {
            return existingSettlement;
        }

        // 기존 정산에는 생성 당시의 요율을 보존하고, 신규 생성에만 현재 계약 요율을 적용한다.
        var merchant = tenants.findById(capture.getMerchantId())
                .orElseThrow(() -> new IllegalStateException("정산할 가맹점을 찾을 수 없습니다."));
        FeeResult fees = feeCalculator.calculate(capture.getCaptureAmount(), merchant.pgFeeRate());

        Settlement settlement = Settlement.scheduled(
                capture.getCaptureId(),
                capture.getPaymentId(),
                capture.getMerchantId(),
                capture.getCaptureAmount(),
                fees.cardFeeRate(),
                fees.pgFeeRate(),
                fees.cardFeeAmount(),
                fees.pgFeeAmount(),
                fees.settlementAmount(),
                Instant.now());

        return settlementRepository.save(settlement);
    }
}
