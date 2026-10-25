package com.donworry.payment.service;

import com.donworry.payment.api.PaymentActionCommand;
import com.donworry.payment.api.PaymentActionReceipt;
import com.donworry.payment.entity.PaymentAction;
import com.donworry.payment.entity.PaymentActionType;
import com.donworry.payment.entity.PaymentStatus;
import com.donworry.payment.repository.PaymentActionRepository;
import com.donworry.payment.repository.PaymentRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentActionAdmissionService {
    private final PaymentRepository payments;
    private final PaymentActionRepository actions;

    // 독립 커밋하지 않는다. 다음 단계 실행 서비스가 저장과 금융 이벤트를 원자적으로 묶는다.
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentActionAdmission admit(PaymentActionCommand command) {
        if (command == null) throw new IllegalArgumentException("command is required");
        var payment = payments.findByIdForUpdate(command.paymentId());
        var keyed = actions.findByIdempotencyKey(command.idempotencyKey());
        if (keyed.isPresent()) {
            var action = keyed.get();
            if (!action.matches(
                    command.paymentId(),
                    command.decisionId(),
                    PaymentActionType.valueOf(command.actionType()),
                    command.idempotencyKey())) return rejected("IDEMPOTENCY_KEY_CONFLICT");
            return new PaymentActionAdmission(action, PaymentActionMapping.receipt(action), false);
        }
        if (payment.isEmpty()) return rejected("PAYMENT_NOT_FOUND");
        if (actions.findByPaymentId(command.paymentId()).isPresent()) return rejected("PAYMENT_ACTION_CONFLICT");
        if (actions.findByDecisionId(command.decisionId()).isPresent()) return rejected("DECISION_ACTION_CONFLICT");
        if (payment.get().getStatus() != PaymentStatus.HELD) return rejected("PAYMENT_NOT_HELD");
        var reference = payment.get().getAuthorizationRef();
        if (reference == null || reference.isBlank()) return rejected("PAYMENT_NOT_AUTHORIZED");
        var action = actions.save(PaymentAction.pending(
                command.paymentId(),
                command.decisionId(),
                PaymentActionType.valueOf(command.actionType()),
                command.idempotencyKey(),
                Instant.now()));
        return new PaymentActionAdmission(action, PaymentActionReceipt.pending(), true);
    }

    private PaymentActionAdmission rejected(String code) {
        return new PaymentActionAdmission(null, PaymentActionReceipt.rejected(code), false);
    }
}
