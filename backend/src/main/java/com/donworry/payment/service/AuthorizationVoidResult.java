package com.donworry.payment.service;

// 기술 실패는 예외다. DECLINED는 확인된 업무 거절만 나타낸다.
enum AuthorizationVoidResult {
    SUCCEEDED,
    DECLINED
}
