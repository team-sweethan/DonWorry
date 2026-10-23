package com.donworry.payment.service;

import com.donworry.payment.entity.Payment;
import com.donworry.payment.entity.PaymentStatus;
import com.donworry.payment.repository.PaymentRepository;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentAuthorizationService {
    private final PaymentRepository paymentRepository;
    private final PaymentAuthorizer authorizer;

    // 별도 Bean 프록시를 통해 호출한다. 잠금은 상태·참조의 커밋/롤백까지 유지된다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    PaymentAuthorizationResult authorize(PaymentAuthorizationContext context) {
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
        // 관리 Entity의 변경 감지로 저장한다. 반환은 프록시 커밋이 끝난 뒤 호출자에게 전달된다.
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
