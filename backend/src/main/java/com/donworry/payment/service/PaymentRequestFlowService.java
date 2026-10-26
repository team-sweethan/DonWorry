package com.donworry.payment.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentRequestFlowService {
    private final PaymentRequestAdmissionService admission;
    private final PaymentRequestProcessingService processing;
    private final PaymentEvaluationService evaluation;

    // 동일 키 POST가 접수 이후 중단된 단계를 재개한다. GET에서 호출하지 않는다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentRequestAdmission submit(PaymentRequestCommand command) {
        var receipt = admission.admit(command);
        var input = processing.evaluationInput(receipt.paymentId());
        if (input.isPresent()) {
            // P5 저장 트랜잭션 종료 뒤 고정 UUID·저장 입력으로 P2를 호출한다.
            var result = evaluateWithRetry(input.get());
            processing.saveEvaluation(receipt.paymentId(), result.evaluation());
        }
        processing.authorize(receipt.paymentId());
        return receipt;
    }

    private PaymentEvaluationResult evaluateWithRetry(PaymentEvaluationInput input) {
        for (int attempt = 0; ; attempt++) {
            try {
                return evaluation.evaluateAndDecide(PaymentRules.State.pending(), input);
            } catch (org.springframework.dao.DataAccessException exception) {
                if (attempt >= 2 || !isEvaluationRace(exception)) throw exception;
            }
        }
    }

    private boolean isEvaluationRace(org.springframework.dao.DataAccessException exception) {
        if (exception instanceof org.springframework.dao.PessimisticLockingFailureException) return true;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.SnapshotIsolationException) return true;
        }
        return false;
    }
}
