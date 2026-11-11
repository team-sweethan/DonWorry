package com.donworry.payment.api;

// 번호 대조 결과 (#619). NO_CONTACT는 이 결제에 저장된 주문자 전화가 없거나 키가 없어 대조하지 못한 경우
public enum ContactMatchResult {
    MATCHED,
    NOT_MATCHED,
    NO_CONTACT
}
