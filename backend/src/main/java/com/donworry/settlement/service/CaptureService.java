package com.donworry.settlement.service;

import com.donworry.settlement.entity.Capture;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CaptureService {
    private final CaptureTransactionService captureTransactionService;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Capture processCapture(Long paymentId, Long merchantId, String orderId, Long amount) {
        try {
            return captureTransactionService.processCapture(paymentId, merchantId, orderId, amount);
        } catch (DataIntegrityViolationException exception) {
            if (!isDuplicatePayment(exception)) {
                throw exception;
            }
            // 실패한 트랜잭션을 끝낸 뒤 새 트랜잭션에서 먼저 완료된 요청의 결과를 읽는다.
            return captureTransactionService.findByPaymentId(paymentId).orElseThrow(() -> exception);
        }
    }

    private boolean isDuplicatePayment(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getErrorCode() == 1062 && "uk_captures_payment".equals(violation.getConstraintName());
            }
        }
        return false;
    }
}
