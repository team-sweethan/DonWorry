package com.donworry.payment.service;

import com.donworry.payment.api.PaymentHeld;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.entity.PaymentStatus;
import com.donworry.payment.repository.PaymentRepository;
import com.donworry.settlement.api.CaptureRequested;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentAuthorizationService {
    private final PaymentRepository paymentRepository;
    private final PaymentAuthorizer authorizer;
    private final ApplicationEventPublisher events;

    // 별도 Bean 프록시를 통해 호출한다. 잠금은 상태·참조의 커밋/롤백까지 유지된다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    PaymentAuthorizationResult authorize(PaymentAuthorizationContext context) {
        return apply(context);
    }

    // 새 요청 처리에서는 결제 상태·이벤트·요청 단계를 호출자 트랜잭션에서 함께 커밋한다.
    @Transactional(propagation = Propagation.MANDATORY)
    PaymentAuthorizationResult authorizeInTransaction(PaymentAuthorizationContext context) {
        return apply(context);
    }

    private PaymentAuthorizationResult apply(PaymentAuthorizationContext context) {
        Objects.requireNonNull(context, "context");
        var payment = paymentRepository
                .findByIdForUpdate(context.payment().paymentId())
                .orElseThrow(() -> new IllegalStateException("결제가 없습니다"));
        requireMatchingPayment(payment, context);
        payment.requireUnapprovedPending();
        var state = PaymentRules.State.pending();
        var verdict = switch (context.evaluationResult().evaluation().result()) {
            case ALLOW -> PaymentRules.FdsVerdict.ALLOW;
            case REVIEW -> PaymentRules.FdsVerdict.REVIEW;
            case DENY -> PaymentRules.FdsVerdict.DENY;
            case ERROR -> PaymentRules.FdsVerdict.ERROR;
        };
        // 평가 당시 Decision이 아니라 잠금으로 확인한 현재 상태에 규칙을 적용한다.
        var decision = PaymentRules.afterFds(state, verdict);
        String declineCode = null;
        if (decision.nextStep() == PaymentRules.NextStep.REQUEST_AUTHORIZATION) {
            var authorization =
                    authorizer.authorize(new AuthorizationCommand(payment.getPaymentId(), payment.getAmount()));
            if (authorization == null) {
                throw new IllegalStateException("승인 응답이 없습니다");
            }
            var outcome = authorization.type() == AuthorizationResult.Type.SUCCEEDED
                    ? PaymentRules.AuthorizationOutcome.SUCCEEDED
                    : PaymentRules.AuthorizationOutcome.DECLINED;
            decision = PaymentRules.afterAuthorization(state, verdict, outcome);
            if (outcome == PaymentRules.AuthorizationOutcome.SUCCEEDED) {
                payment.recordAuthorizationSuccess(
                        authorization.authorizationRef(), decision.state().status() == PaymentStatus.HELD);
            } else {
                payment.declineAuthorization();
                declineCode = authorization.declineCode();
            }
        } else if (decision.state().status() == PaymentStatus.DENIED) {
            payment.deny();
        }
        // 승인 저장과 등록된 수신자의 발행 기록이 함께 커밋되도록 업무 트랜잭션 안에서 발행한다.
        if (decision.nextStep() == PaymentRules.NextStep.REQUEST_CAPTURE) {
            events.publishEvent(CaptureRequested.builder()
                    .paymentId(payment.getPaymentId())
                    .merchantId(payment.getMerchantId())
                    .orderId(payment.getOrderRef())
                    .amount(payment.getAmount())
                    .build());
        } else if (decision.nextStep() == PaymentRules.NextStep.NOTIFY_HELD) {
            events.publishEvent(PaymentHeld.builder()
                    .paymentId(payment.getPaymentId())
                    .merchantId(payment.getMerchantId())
                    .customerRef(payment.getCustomerRef())
                    .amount(payment.getAmount())
                    .authorizationRef(payment.getAuthorizationRef())
                    .evaluationId(context.evaluationResult().evaluation().evaluationId())
                    .heldAt(Instant.now())
                    .build());
        }
        // 승인 단계 스냅샷이다. 완료 이벤트가 반영된 최신 결제 상태를 뜻하지 않는다.
        return PaymentAuthorizationResult.builder()
                .paymentId(payment.getPaymentId())
                .evaluationResult(context.evaluationResult())
                .status(payment.getStatus())
                .authorizationRef(payment.getAuthorizationRef())
                .nextStep(decision.nextStep())
                .declineCode(declineCode)
                .build();
    }

    private void requireMatchingPayment(Payment payment, PaymentAuthorizationContext context) {
        var snapshot = context.payment();
        if (!Objects.equals(payment.getPaymentId(), snapshot.paymentId())
                || !Objects.equals(payment.getCustomerRef(), snapshot.customerRef())
                || !Objects.equals(payment.getAmount(), snapshot.amount())
                || !Objects.equals(payment.getCreatedAt(), snapshot.createdAt())
                || !context.evaluationRequestId()
                        .equals(context.evaluationResult().evaluation().evaluationRequestId())) {
            throw new IllegalStateException("저장 결제와 평가 문맥이 일치하지 않습니다");
        }
    }
}
