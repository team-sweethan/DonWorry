package com.donworry.payment.service;

// 외부 카드사 호출·독립 저장·금융 부작용이 없는 모의 취소 전용 경계다.
interface PaymentVoider {
    AuthorizationVoidResult voidAuthorization(AuthorizationVoidCommand command);
}
