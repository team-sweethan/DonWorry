package com.donworry.identity.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

@Builder
public record AdminUserResponse(
        Long userId,
        String loginId,
        String displayName,
        String role,
        String status,
        Long merchantId,
        String merchantName,
        List<String> permissions,
        boolean passwordChangeRequired,
        LocalDateTime createdAt) {}
