package com.donworry.payment.service;

// 저장된 승인 참조만 전달한다. 외부 요청에서 받은 참조를 대신 사용하지 않는다.
record AuthorizationVoidCommand(Long paymentId, String authorizationRef) {
    AuthorizationVoidCommand {
        if (paymentId == null
                || paymentId <= 0
                || authorizationRef == null
                || authorizationRef.isBlank()
                || authorizationRef.codePointCount(0, authorizationRef.length()) > 100) {
            throw new IllegalArgumentException("유효한 결제 ID와 승인 참조가 필요합니다");
        }
    }
}
