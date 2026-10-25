package com.donworry.payment.service;

import java.util.UUID;
import lombok.Builder;

// 접수 커밋 결과다. 결제 완료를 뜻하지 않으며 평가·승인은 이 커밋 이후 진행한다.
@Builder
public record PaymentRequestAdmission(Long paymentId, Long attemptNo, UUID evaluationRequestId, boolean created) {}
