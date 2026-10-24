package com.donworry.identity.api;

import java.util.Optional;

public interface TenantReader {
    // 없는 가맹점만 empty다. INACTIVE도 반환하며, null·0 이하 ID는 IllegalArgumentException이다.
    Optional<TenantSummary> findById(Long merchantId);
}
