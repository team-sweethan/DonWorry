package com.donworry.payment.service;

import static com.donworry.payment.service.PaymentRequestConflictException.Code.*;

import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class PaymentRequestTransactionService {
    private final PaymentOrderRepository orders;
    private final PaymentRequestRepository requests;
    private final PaymentRepository payments;

    // 잠금 대기 전에 읽은 키/최신 시도가 대기 후에도 오래된 스냅샷으로 남지 않게 한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public PaymentRequestAdmission admit(PaymentRequestCommand command) {
        var existing = requests.findByMerchantIdAndIdempotencyKey(command.merchantId(), command.idempotencyKey());
        if (existing.isPresent()) return replay(command, existing.get());

        var order = orders.findByMerchantIdAndOrderRefForUpdate(command.merchantId(), command.orderRef())
                .orElseGet(() -> orders.save(PaymentOrder.register(
                        command.merchantId(),
                        command.orderRef(),
                        command.customerRef(),
                        command.amount(),
                        command.orderCreatedAt(),
                        Instant.now())));
        // 같은 주문의 선행 접수가 커밋되는 동안 기다렸다면 그 키를 다시 확인한다.
        existing = requests.findByMerchantIdAndIdempotencyKey(command.merchantId(), command.idempotencyKey());
        if (existing.isPresent()) return replay(command, existing.get());
        if (!matches(order, command)) throw new PaymentRequestConflictException(ORDER_CONTENT_CONFLICT);
        if (payments.countUntrackedByMerchantIdAndOrderRef(command.merchantId(), command.orderRef()) > 0)
            throw new PaymentRequestConflictException(LEGACY_ORDER_EXISTS);

        var latest = requests.findFirstByPaymentOrderIdOrderByAttemptNoDesc(order.getPaymentOrderId());
        if (latest.isPresent()) {
            var previous =
                    payments.findByIdForUpdate(latest.get().getPaymentId()).orElseThrow();
            if (previous.getStatus() != PaymentStatus.DENIED && previous.getStatus() != PaymentStatus.DECLINED)
                throw new PaymentRequestConflictException(ORDER_PAYMENT_EXISTS);
        }
        var now = Instant.now();
        if (!now.isBefore(order.getPaymentDeadlineAt()))
            throw new PaymentRequestConflictException(ORDER_DEADLINE_PASSED);
        long attempt = order.allocateAttempt(now);
        var payment = payments.save(Payment.pending(
                command.merchantId(), command.customerRef(), command.orderRef(), command.amount(), now));
        var request = requests.save(
                PaymentRequest.received(order, payment, attempt, command.idempotencyKey(), UUID.randomUUID()));
        return receipt(request, true);
    }

    private PaymentRequestAdmission replay(PaymentRequestCommand command, PaymentRequest request) {
        var order = orders.findById(request.getPaymentOrderId()).orElseThrow();
        if (!matches(order, command)) throw new PaymentRequestConflictException(IDEMPOTENCY_KEY_CONFLICT);
        return receipt(request, false);
    }

    private boolean matches(PaymentOrder order, PaymentRequestCommand command) {
        return order.getMerchantId().equals(command.merchantId())
                && order.matches(command.orderRef(), command.customerRef(), command.amount(), command.orderCreatedAt());
    }

    private PaymentRequestAdmission receipt(PaymentRequest request, boolean created) {
        return new PaymentRequestAdmission(
                request.getPaymentId(), request.getAttemptNo(), request.getEvaluationRequestId(), created);
    }
}
