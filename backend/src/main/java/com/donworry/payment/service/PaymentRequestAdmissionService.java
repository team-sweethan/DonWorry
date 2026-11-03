package com.donworry.payment.service;

import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentRequestAdmissionService {
    private static final Set<String> RACE_UNIQUES =
            Set.of("uk_payment_orders_merchant_order", "uk_payment_requests_merchant_key");
    private final PaymentRequestTransactionService transaction;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentRequestAdmission admit(PaymentRequestCommand command) {
        Objects.requireNonNull(command, "command");
        for (int attempt = 0; ; attempt++) {
            try {
                return transaction.admit(command);
            } catch (DataIntegrityViolationException exception) {
                // 최초 주문 INSERT와 서로 다른 주문의 동일 키 경합만 새 트랜잭션으로 재조회한다.
                if (attempt >= 2 || !isAdmissionRace(exception)) throw exception;
            }
        }
    }

    private boolean isAdmissionRace(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getErrorCode() == 1062
                        && violation.getConstraintName() != null
                        && RACE_UNIQUES.contains(violation.getConstraintName());
            }
        }
        return false;
    }
}
