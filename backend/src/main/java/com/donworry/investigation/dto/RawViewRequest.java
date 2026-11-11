package com.donworry.investigation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 전화 원문 열람 근거 (#619) — 이의제기 참조 번호 · 가맹점 연락 시도 · 사유. 고객 이름 · 번호는 적지 않는다(화면 안내)
public record RawViewRequest(
        @NotBlank @Size(max = 100) String disputeRef,
        @NotBlank @Size(max = 200) String merchantContactAttempt,
        @NotBlank @Size(max = 500) String reason) {}
