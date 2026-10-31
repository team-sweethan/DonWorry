package com.donworry.payment.service;

import com.donworry.payment.dto.PaymentCancellationResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MerchantPaymentCancellationService {
    private final PaymentMerchantAccess merchants;
    private final PaymentCancellationAdmissionService admission;
    private final PaymentCancellationQueryService queries;

    public PaymentCancellationResponse request(Long paymentId, UUID key, String reason) {
        // 정지 가맹점도 기존 결제를 취소할 수 있다. 인증과 소유 확인은 유지한다.
        return admission.admit(
                new PaymentCancellationCommand(merchants.requireMerchant(false), paymentId, key, reason));
    }

    public PaymentCancellationResponse find(UUID key) {
        return queries.find(merchants.requireMerchant(false), key);
    }
}
