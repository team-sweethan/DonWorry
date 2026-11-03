package com.donworry.identity.security;

import com.donworry.identity.api.StaffSummary;
import com.donworry.identity.entity.UserRole;
import java.util.List;

public final class SessionUserMapper {
    /** ADMIN은 저장된 권한 없이 역할만으로 모든 조회 권한을 가진다. */
    public static final List<String> ADMIN_PERMISSIONS = List.of("FDS_VIEW", "FINANCE_VIEW");

    private SessionUserMapper() {}

    public static StaffSummary toSummary(SessionUser user) {
        return StaffSummary.builder()
                .userId(user.userId())
                .displayName(user.displayName())
                .role(user.role().name())
                .merchantId(user.merchantId())
                .permissions(user.role() == UserRole.ADMIN ? ADMIN_PERMISSIONS : user.permissions())
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
