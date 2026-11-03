package com.donworry.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 100) String loginId, @NotBlank String password) {
    // record의 기본 toString이 비밀번호를 출력하지 않도록 한다.
    @Override
    public String toString() {
        return "LoginRequest[credentials=REDACTED]";
    }
}
