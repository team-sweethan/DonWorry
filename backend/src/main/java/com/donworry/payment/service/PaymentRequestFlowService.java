package com.donworry.payment.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRequestFlowService {
    private final PaymentRequestAdmissionService admission;
    private final PaymentRequestProcessingService processing;
    private final PaymentEvaluationService evaluation;
    private final com.donworry.fds.api.FdsFailureReporter failureReporter;

    // 동일 키 POST가 접수 이후 중단된 단계를 재개한다. GET에서 호출하지 않는다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentRequestAdmission submit(PaymentRequestCommand command) {
        var receipt = admission.admit(command);
        var input = processing.evaluationInput(receipt.paymentId());
        if (input.isPresent()) {
            // P5 저장 트랜잭션 종료 뒤 고정 UUID·저장 입력으로 P2를 호출한다.
            var result = evaluateWithRetry(input.get());
            // REQUIRES_NEW가 성공적으로 커밋된 뒤에만 보고한다. 보고 실패는 저장된 ERROR를 되돌리지 않는다.
            var report = processing.saveEvaluation(receipt.paymentId(), result);
            report.ifPresent(value -> {
                try {
                    failureReporter.report(value);
                } catch (RuntimeException exception) {
                    // 예외 메시지·원본 입력은 로그에 남기지 않는다. 복구는 #275의 직원 POST로 수행한다.
                    log.warn(
                            "FDS 오류 보고 실패 paymentId={} failureReportId={} exceptionType={}",
                            value.paymentId(),
                            value.failureReportId(),
                            exception.getClass().getSimpleName());
                }
            });
        }
        processing.authorize(receipt.paymentId());
        return receipt;
    }

    private PaymentObservedEvaluation evaluateWithRetry(PaymentEvaluationInput input) {
        for (int attempt = 0; ; attempt++) {
            try {
                return evaluation.evaluateObserved(input);
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
