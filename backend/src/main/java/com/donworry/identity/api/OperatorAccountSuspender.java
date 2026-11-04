package com.donworry.identity.api;

public interface OperatorAccountSuspender {
    // OPERATOR만 정지한다. 이미 INACTIVE면 변경 없이 성공한다.
    // 호출자 트랜잭션과 독립 커밋하며, 반환 전에 기존 세션을 만료한다.
    // null·0 이하 ID는 IllegalArgumentException, 없는 계정·직원은 공개 전용 예외다.
    void suspend(Long userId);
}
