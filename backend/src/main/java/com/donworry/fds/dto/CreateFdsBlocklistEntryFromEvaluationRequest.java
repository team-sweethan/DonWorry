package com.donworry.fds.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 경보 상세에서 차단 후보를 등록한다. 식별값은 보내지 않고 서버가 평가에서 찾는다 (#603)
public record CreateFdsBlocklistEntryFromEvaluationRequest(
        @NotNull CreateFdsBlocklistEntryRequest.IdentifierType identifierType,
        @NotBlank @Size(max = 500) String reason) {}
