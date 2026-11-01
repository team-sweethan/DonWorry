package com.donworry.payment.service;

import com.donworry.payment.dto.PaymentCancellationResponse;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import com.donworry.settlement.api.CaptureReader;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class PaymentCancellationTransactionService {
    private final PaymentRepository payments;
    private final PaymentActionRepository actions;
    private final PaymentRequestRepository originals;
    private final PaymentCancellationRequestRepository requests;
    private final PaymentCancellationRepository cancellations;
    private final CaptureReader captures;
    private final PaymentCancellationDecisionService decisions;
    private final PaymentCancellationResultService results;
    private final PaymentCustomerVoidService customerVoid;
    private final PaymentCustomerRefundService customerRefund;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public PaymentCancellationResponse admit(PaymentCancellationCommand command) {
        var payment = payments.findOwnedForCancellation(command.paymentId(), command.merchantId())
                .orElseThrow(() -> new PaymentHttpException(404, "PAYMENT_NOT_FOUND", "결제를 찾을 수 없습니다."));
        var request = requests.findByMerchantIdAndRequestKey(command.merchantId(), command.requestKey())
                .orElse(null);
        if (request != null) {
            if (!request.matches(command.paymentId(), command.reason()))
                throw new PaymentHttpException(409, "IDEMPOTENCY_KEY_CONFLICT", "최초 취소 요청과 내용이 다릅니다.");
            if (request.getResolution() != PaymentCancellationResolution.DEFERRED
                    && request.getResolution() != PaymentCancellationResolution.REVIEW_REQUIRED)
                return results.result(request, payment);
        }
        var action = actions.findByPaymentId(payment.getPaymentId()).orElse(null);
        var work = cancellations.findByPaymentId(payment.getPaymentId());
        var capture = work.isPresent()
                ? null
                : captures.findByPaymentId(payment.getPaymentId()).orElse(null);
        var resolution = work.isPresent()
                ? PaymentCancellationResolution.LINKED
                : decisions.decide(
                        payment,
                        originals.findByPaymentId(payment.getPaymentId()).orElse(null),
                        action,
                        capture);
        if (resolution == PaymentCancellationResolution.ACCEPTED) {
            if (payment.getStatus() == PaymentStatus.CAPTURED) customerRefund.requireReady();
            else customerVoid.requireReady();
        }
        var legacy = resolution == PaymentCancellationResolution.LEGACY_VOID ? action : null;
        if (request == null) {
            request = requests.save(PaymentCancellationRequest.recordDecision(
                    payment, command.requestKey(), command.reason(), resolution, legacy, Instant.now()));
        } else {
            request.reconsider(payment, resolution, legacy, Instant.now());
        }
        if (resolution == PaymentCancellationResolution.ACCEPTED) {
            if (payment.getStatus() == PaymentStatus.CAPTURED) customerRefund.execute(request, payment, capture);
            else customerVoid.execute(request, payment);
        }
        // 업무 거절도 정상 반환해야 키·최초 내용이 커밋된다. HTTP 상태 변환은 커밋 밖에서 한다.
        return results.result(request, payment);
    }
}
