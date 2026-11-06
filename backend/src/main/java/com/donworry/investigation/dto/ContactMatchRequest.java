package com.donworry.investigation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 대조할 번호 — 고객이 말한 번호 그대로 (하이픈 · 공백 허용). 저장 · 기록하지 않는다 (#619)
public record ContactMatchRequest(@NotBlank @Size(max = 20) String phone) {
    @Override
    public String toString() {
        return "ContactMatchRequest[생략]";
    }
}
