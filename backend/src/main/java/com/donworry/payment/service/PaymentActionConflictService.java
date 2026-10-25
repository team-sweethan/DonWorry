package com.donworry.payment.service;

import com.donworry.payment.api.PaymentActionCommand;
import com.donworry.payment.api.PaymentActionReceipt;
import com.donworry.payment.entity.PaymentActionType;
import com.donworry.payment.repository.PaymentActionRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentActionConflictService {
    private final PaymentActionRepository actions;

    // UNIQUE 경쟁에서 진 트랜잭션이 종료된 후 새 스냅샷으로 승자의 요청을 대조한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<PaymentActionReceipt> resolve(PaymentActionCommand command) {
        var keyed = actions.findByIdempotencyKey(command.idempotencyKey());
        if (keyed.isPresent()) {
            var action = keyed.get();
            return Optional.of(
                    action.matches(
                                    command.paymentId(),
                                    command.decisionId(),
                                    PaymentActionType.valueOf(command.actionType()),
                                    command.idempotencyKey())
                            ? PaymentActionMapping.receipt(action)
                            : PaymentActionReceipt.rejected("IDEMPOTENCY_KEY_CONFLICT"));
        }
        if (actions.findByPaymentId(command.paymentId()).isPresent())
            return Optional.of(PaymentActionReceipt.rejected("PAYMENT_ACTION_CONFLICT"));
        if (actions.findByDecisionId(command.decisionId()).isPresent())
            return Optional.of(PaymentActionReceipt.rejected("DECISION_ACTION_CONFLICT"));
        return Optional.empty();
    }
}
