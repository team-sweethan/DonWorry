package com.donworry.identity.api;

import java.util.List;
import lombok.Builder;

@Builder
public record StaffSummary(Long userId, String role, Long merchantId, List<String> permissions) {
    public StaffSummary {
        // 권한 필드가 없던 이전 세션·테스트 데이터는 권한 없음으로 본다
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}
