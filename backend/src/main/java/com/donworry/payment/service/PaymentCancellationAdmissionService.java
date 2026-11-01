package com.donworry.payment.service;

import com.donworry.payment.dto.PaymentCancellationResponse;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentCancellationAdmissionService {
    private final PaymentCancellationTransactionService transaction;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentCancellationResponse admit(PaymentCancellationCommand command) {
        Objects.requireNonNull(command, "command");
        for (int attempt = 0; ; attempt++) {
            try {
                return transaction.admit(command);
            } catch (DataIntegrityViolationException exception) {
                // 서로 다른 결제가 같은 키를 잡은 경우만 종료된 트랜잭션 밖에서 다시 대조한다.
                if (attempt >= 2 || !isRequestKeyRace(exception)) throw exception;
            }
        }
    }

    private boolean isRequestKeyRace(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation)
                return violation.getErrorCode() == 1062
                        && "uk_payment_cancellation_requests_merchant_key".equals(violation.getConstraintName());
        }
        return false;
    }
}
