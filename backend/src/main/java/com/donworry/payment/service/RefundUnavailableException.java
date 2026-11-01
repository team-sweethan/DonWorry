package com.donworry.payment.service;

// 외부 부작용 없는 모의 실행의 기술 실패이며 외부 금융 결과 불명과 구별한다.
class RefundUnavailableException extends RuntimeException {
    RefundUnavailableException(String message) {
        super(message);
    }
}
