package com.donworry.identity.api;

import java.util.Optional;

public interface TenantReader {
    // 없는 가맹점만 empty다. INACTIVE도 반환하며, null·0 이하 ID는 IllegalArgumentException이다.
    // pgFeeRate는 비율 단위다 (0.02 = 2%). 조회 자체는 정산 계산이나 인가를 수행하지 않는다.
    Optional<TenantSummary> findById(Long merchantId);
}
