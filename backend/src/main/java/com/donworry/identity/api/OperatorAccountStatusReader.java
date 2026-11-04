package com.donworry.identity.api;

import java.util.Optional;

public interface OperatorAccountStatusReader {
    // 없는 계정·OPERATOR가 아닌 계정은 empty다. INACTIVE인 OPERATOR도 반환한다.
    // null·0 이하 ID는 IllegalArgumentException이며 DB 오류는 전파한다.
    // 호출자 트랜잭션과 분리된 스냅샷이다. 조회 후 정지 호출까지의 원자성을 보장하지 않는다.
    Optional<OperatorAccountStatusSnapshot> findById(Long userId);
}
