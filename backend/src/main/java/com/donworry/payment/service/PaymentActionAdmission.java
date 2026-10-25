package com.donworry.payment.service;

import com.donworry.payment.api.PaymentActionReceipt;
import com.donworry.payment.entity.PaymentAction;

// 내부 실행 서비스만 쓴다. created일 때 같은 트랜잭션에서 매입 발행 또는 모의 취소를 이어야 한다.
public record PaymentActionAdmission(PaymentAction action, PaymentActionReceipt receipt, boolean created) {}
