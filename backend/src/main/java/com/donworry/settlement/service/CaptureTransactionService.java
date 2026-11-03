package com.donworry.settlement.service;

import com.donworry.settlement.api.CaptureCompleted;
import com.donworry.settlement.entity.Capture;
import com.donworry.settlement.repository.CaptureRepository;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CaptureTransactionService {

    private final CaptureRepository captureRepository;
    private final SettlementService settlementService;
    private final ApplicationEventPublisher events;
    private final OrderFulfillmentService fulfillments;

    // 중복 저장이 실패해도 호출자가 롤백된 트랜잭션 밖에서 기존 결과를 조회할 수 있게 한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Capture processCapture(Long paymentId, Long merchantId, String orderId, Long amount) {

        if (paymentId == null
                || merchantId == null
                || orderId == null
                || orderId.isBlank()
                || amount == null
                || amount <= 0) {
            throw new IllegalArgumentException("올바르지 않은 매입 요청입니다.");
        }

        fulfillments.lockForCapture(orderId, merchantId);
        Capture existingCapture = captureRepository.findByPaymentId(paymentId).orElse(null);

        if (existingCapture != null) {
            if (!existingCapture.getOrderId().equals(orderId)
                    || !existingCapture.getMerchantId().equals(merchantId)
                    || !existingCapture.getCaptureAmount().equals(amount)) {
                throw new IllegalStateException("기존 매입 요청 내용과 다릅니다.");
            }
            return existingCapture;
        }

        Instant now = Instant.now();

        Capture capture = Capture.processing(paymentId, merchantId, orderId, amount, now);

        capture.complete(now);

        Capture savedCapture = captureRepository.save(capture);

        // 정산 예정금이 없는 매입 완료 건이 남지 않도록 같은 트랜잭션에서 생성한다.
        var settlement = settlementService.createSettlement(savedCapture);
        fulfillments.connectCapture(savedCapture, settlement);

        // 저장이 롤백될 때 완료 통지도 남지 않도록 같은 트랜잭션에서 발행한다.
        events.publishEvent(CaptureCompleted.builder()
                .paymentId(savedCapture.getPaymentId())
                .captureId(savedCapture.getCaptureId())
                .capturedAt(savedCapture.getCapturedAt())
                .build());

        return savedCapture;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<Capture> findByPaymentId(Long paymentId) {
        return captureRepository.findByPaymentId(paymentId);
    }
}
