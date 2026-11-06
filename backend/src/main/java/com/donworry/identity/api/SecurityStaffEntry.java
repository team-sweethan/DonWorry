package com.donworry.identity.api;

import java.util.Objects;

// 보안 직원 한 명 — 로그인 ID · 권한 · 연락처는 내보내지 않는다
public record SecurityStaffEntry(Long userId, String displayName, boolean active) {
    public SecurityStaffEntry {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(displayName, "displayName");
    }
}
