package com.donworry.payment.service;

import com.donworry.fds.api.FdsEvaluationFailureReport;
import com.donworry.payment.dto.PaymentFdsRecoveryResponse;

// report는 WAITING_ANALYSIS일 때만 존재한다. Entity를 트랜잭션 밖에 전달하지 않는다.
record PaymentFdsDeliverySnapshot(PaymentFdsRecoveryResponse summary, FdsEvaluationFailureReport report) {}
