package com.donworry.identity.api;

import java.util.Optional;

public interface CurrentStaff {
    // 비로그인·익명 인증은 empty, 예상하지 못한 인증 principal은 내부 오류다.
    Optional<StaffSummary> findCurrent();
}
