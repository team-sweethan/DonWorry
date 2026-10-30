package com.donworry.payment.repository;

import com.donworry.payment.entity.Payment;
import com.donworry.payment.entity.PaymentRequest;

// 요청 기록이 없는 결제는 request가 null이다
public record PaymentTransactionRow(Payment payment, PaymentRequest request) {}
