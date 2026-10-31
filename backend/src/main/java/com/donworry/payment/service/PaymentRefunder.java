package com.donworry.payment.service;

// 외부 HTTP·독립 저장·금융 부작용이 없는 모의 전액 환불 경계다.
interface PaymentRefunder {
    RefundResult refund(RefundCommand command);
}
