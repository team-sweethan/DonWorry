package com.donworry.identity.security;

import com.donworry.identity.entity.UserRole;
import java.io.Serializable;
import java.util.List;
import lombok.Builder;

@Builder
public record SessionUser(
        Long userId, String loginId, String displayName, UserRole role, Long merchantId, List<String> permissions)
        implements Serializable {}
