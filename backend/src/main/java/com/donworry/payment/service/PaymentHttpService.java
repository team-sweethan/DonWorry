package com.donworry.payment.service;

import com.donworry.payment.dto.*;
import com.donworry.payment.entity.PaymentRiskSignals;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentHttpService {
    private final PaymentRequestFlowService flow;
    private final MerchantPaymentQueryService query;

    public boolean processing(MerchantPaymentResponse response) {
        return response.paymentStatus() == com.donworry.payment.entity.PaymentStatus.PENDING
                || response.paymentStatus() == com.donworry.payment.entity.PaymentStatus.HELD;
    }

    public MerchantPaymentResponse submit(Long merchantId, String key, PaymentSubmitRequest body) {
        PaymentRequestCommand command;
        try {
            if (key == null
                    || !key.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                throw new IllegalArgumentException("UUID key required");
            long customer = Long.parseLong(body.customerRef());
            if (customer <= 0
                    || !body.orderCreatedAt().equals(body.orderCreatedAt().truncatedTo(ChronoUnit.MICROS)))
                throw new IllegalArgumentException("invalid input");
            command = new PaymentRequestCommand(
                    merchantId,
                    body.orderRef(),
                    merchantId + ":" + customer,
                    body.amount(),
                    body.orderCreatedAt(),
                    UUID.fromString(key),
                    new PaymentRiskSignals(
                            body.deviceId(),
                            body.cardToken(),
                            body.cardDisplayLabel(),
                            body.ipCountryCode(),
                            body.ipAddress(),
                            body.buyerEmail()));
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        // 검증 이후 내부 처리 오류를 400 입력 오류로 바꾸지 않는다.
        var receipt = flow.submit(command);
        return findPayment(merchantId, receipt.paymentId());
    }

    public MerchantPaymentResponse findPayment(Long merchantId, Long paymentId) {
        if (paymentId == null || paymentId <= 0) throw invalid();
        return query.findPayment(merchantId, paymentId)
                .orElseThrow(() -> new PaymentHttpException(404, "PAYMENT_NOT_FOUND", "결제를 찾을 수 없습니다."));
    }

    public MerchantPaymentOrderResponse findOrder(
            Long merchantId, String orderRef, Integer size, Long beforeAttemptNo) {
        if (orderRef == null
                || orderRef.isBlank()
                || !orderRef.equals(orderRef.strip())
                || orderRef.codePointCount(0, orderRef.length()) > 100
                || (size != null && (size < 1 || size > 100))
                || (beforeAttemptNo != null && beforeAttemptNo <= 0)) throw invalid();
        return query.findOrder(merchantId, orderRef, size, beforeAttemptNo)
                .orElseThrow(() -> new PaymentHttpException(404, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."));
    }

    private PaymentHttpException invalid() {
        return new PaymentHttpException(400, "INVALID_REQUEST", "요청 형식을 확인해주세요.");
    }
}
