package com.donworry.identity.security;

import com.donworry.identity.entity.UserRole;
import java.io.Serializable;
import lombok.Builder;

@Builder
public record SessionUser(Long userId, String loginId, String displayName, UserRole role, Long merchantId)
        implements Serializable {}
