package com.donworry.payment.service;

import com.donworry.payment.dto.PaymentCancellationResponse;
import com.donworry.payment.repository.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class PaymentCancellationQueryService {
    private final PaymentRepository payments;
    private final PaymentCancellationRequestRepository requests;
    private final PaymentCancellationResultService results;

    // 요청과 결과를 같은 스냅샷에서 읽는다. 재판단·금융 실행·P6 조회를 하지 않는다.
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public PaymentCancellationResponse find(Long merchantId, UUID key) {
        if (merchantId == null || merchantId <= 0 || key == null)
            throw new PaymentHttpException(400, "INVALID_CANCELLATION_REQUEST", "조회 요청 형식을 확인해주세요.");
        var request = requests.findByMerchantIdAndRequestKey(merchantId, key).orElseThrow(() -> notFound());
        var payment = payments.findByPaymentIdAndMerchantId(request.getPaymentId(), merchantId)
                .orElseThrow(() -> notFound());
        return results.result(request, payment);
    }

    private PaymentHttpException notFound() {
        return new PaymentHttpException(404, "CANCELLATION_REQUEST_NOT_FOUND", "취소 요청을 찾을 수 없습니다.");
    }
}
