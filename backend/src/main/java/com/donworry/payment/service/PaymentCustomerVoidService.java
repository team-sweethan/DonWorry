package com.donworry.payment.service;

import com.donworry.payment.api.PaymentCustomerVoidSucceeded;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.PaymentCancellationRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentCustomerVoidService {
    private final PaymentVoider voider;
    private final PaymentCancellationRepository cancellations;
    private final ObjectProvider<PaymentCustomerVoidCompletion> completion;

    public void requireReady() {
        publisher();
    }

    // 호출자가 결제 잠금·소유·매입/직원 작업 대조를 마쳤다. 독립 커밋이나 외부 HTTP 호출은 없다.
    @Transactional(propagation = Propagation.MANDATORY)
    public void execute(PaymentCancellationRequest request, Payment payment) {
        var publisher = publisher();
        if (payment.getStatus() != PaymentStatus.HELD
                || cancellations.findByPaymentId(payment.getPaymentId()).isPresent()) {
            throw new IllegalStateException("신규 HELD 승인 취소 작업이 아닙니다");
        }
        var work = cancellations.save(
                PaymentCancellation.start(request, payment, PaymentCancellationOperation.VOID, null, Instant.now()));
        AuthorizationVoidResult result;
        try {
            result = voider.voidAuthorization(
                    new AuthorizationVoidCommand(payment.getPaymentId(), payment.getAuthorizationRef()));
        } catch (AuthorizationVoidUnavailableException e) {
            throw unavailable();
        }
        if (result == null) throw unavailable();
        if (result == AuthorizationVoidResult.DECLINED) {
            work.fail("VOID_DECLINED", Instant.now());
            return;
        }
        payment.completeVoid();
        // 결제 전이 시각을 작업·통지의 확정 시각으로 함께 사용한다.
        work.succeed(payment.getStatusChangedAt());
        publisher.publish(new PaymentCustomerVoidSucceeded(
                work.getCancellationId(),
                payment.getPaymentId(),
                payment.getMerchantId(),
                work.getAmount(),
                work.getCompletedAt()));
    }

    private PaymentCustomerVoidCompletion publisher() {
        var publisher = completion.getIfAvailable();
        if (publisher == null) {
            throw new PaymentHttpException(503, "CANCELLATION_NOT_READY", "취소 실행 연결이 준비되지 않았습니다.");
        }
        return publisher;
    }

    private PaymentHttpException unavailable() {
        return new PaymentHttpException(503, "CANCELLATION_UNAVAILABLE", "처리 결과를 확인할 수 없습니다. 같은 요청 키로 확인해주세요.");
    }
}
