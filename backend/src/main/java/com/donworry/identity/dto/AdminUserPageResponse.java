package com.donworry.identity.dto;

import java.util.List;
import lombok.Builder;

@Builder
public record AdminUserPageResponse(List<AdminUserResponse> items, int page, int size, long totalElements) {}
