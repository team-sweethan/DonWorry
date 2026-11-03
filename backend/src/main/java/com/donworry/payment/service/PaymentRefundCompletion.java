package com.donworry.payment.service;

import com.donworry.settlement.api.PaymentRefundSucceeded;

// 실제 P6 수신·재전달 검증 뒤 발행 어댑터를 등록한다. 운영 Bean은 아직 없다.
interface PaymentRefundCompletion {
    void publish(PaymentRefundSucceeded event);
}
