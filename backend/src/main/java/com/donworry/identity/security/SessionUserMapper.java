package com.donworry.identity.security;

import com.donworry.identity.api.StaffSummary;
import com.donworry.identity.entity.UserRole;
import java.util.List;

public final class SessionUserMapper {
    private SessionUserMapper() {}

    public static StaffSummary toSummary(SessionUser user) {
        return StaffSummary.builder()
                .userId(user.userId())
                .role(user.role().name())
                .merchantId(user.merchantId())
                .permissions(user.role() == UserRole.ADMIN ? List.of("FDS_VIEW", "FINANCE_VIEW") : user.permissions())
                .build();
    }

    public static String tenantScope(SessionUser user) {
        // ALL은 가맹점 조회 범위이며 기능 권한을 부여하지 않는다.
        return switch (user.role()) {
            case OPERATOR -> "MERCHANT";
            case SECURITY_STAFF, ADMIN -> "ALL";
        };
    }
}
