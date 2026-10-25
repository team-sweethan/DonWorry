package com.donworry.identity.dto;

import java.util.List;
import lombok.Builder;

@Builder
public record AdminMerchantPageResponse(List<AdminMerchantResponse> items, int page, int size, long totalElements) {}
