package com.donworry.payment.service;

// 외부 부작용 없는 모의 실행의 식별 가능한 기술 실패다. 실제 카드사 결과 불명을 뜻하지 않는다.
class AuthorizationVoidUnavailableException extends RuntimeException {
    AuthorizationVoidUnavailableException(String message) {
        super(message);
    }
}
