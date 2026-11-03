package com.donworry.payment.service;

import com.donworry.payment.dto.PaymentFdsRecoveryResponse;

// 상태 변경으로 수동 종료한 경우에도 종료 커밋 후 호출자가 업무 거절을 반환한다.
public record PaymentFdsReevaluationExecution(
        PaymentFdsRecoveryResponse response, PaymentFdsReevaluationNotAllowedException.Reason rejection) {}
