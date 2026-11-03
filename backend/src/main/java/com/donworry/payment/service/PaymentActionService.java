package com.donworry.payment.service;

import com.donworry.payment.api.PaymentActionCommand;
import com.donworry.payment.api.PaymentActionReceipt;
import com.donworry.payment.api.PaymentActionRequester;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentActionService implements PaymentActionRequester {
    private static final Set<String> REQUEST_UNIQUES =
            Set.of("uk_payment_actions_payment", "uk_payment_actions_decision", "uk_payment_actions_idempotency_key");
    private final PaymentActionTransactionService transaction;
    private final PaymentActionConflictService conflicts;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentActionReceipt request(PaymentActionCommand command) {
        if (command == null) throw new IllegalArgumentException("command is required");
        try {
            return transaction.request(command);
        } catch (DataIntegrityViolationException exception) {
            if (!isRequestConflict(exception)) throw exception;
            return conflicts.resolve(command).orElseThrow(() -> exception);
        }
    }

    private boolean isRequestConflict(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getErrorCode() == 1062
                        && violation.getConstraintName() != null
                        && REQUEST_UNIQUES.contains(violation.getConstraintName());
            }
        }
        return false;
    }
}
