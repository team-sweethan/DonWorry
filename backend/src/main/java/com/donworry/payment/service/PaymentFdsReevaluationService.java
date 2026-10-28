package com.donworry.payment.service;

import com.donworry.payment.dto.PaymentFdsRecoveryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class PaymentFdsReevaluationService {
    private final PaymentFdsReevaluationAdmissionService admission;
    private final PaymentFdsReevaluationExecutionService execution;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentFdsRecoveryResponse reevaluate(Long paymentId) {
        var work = admission.admit(paymentId);
        var result = execution.execute(paymentId, work.retryId());
        if (result.rejection() != null) throw new PaymentFdsReevaluationNotAllowedException(result.rejection());
        return result.response();
    }
}
