package com.donworry.fds.dto;

import jakarta.validation.constraints.Size;

// ADMIN "정상 업무로 닫기" 메모 (#516) — 화면에서 개인정보를 쓰지 말라고 안내한다
public record CloseInsiderAlertRequest(@Size(max = 200) String memo) {}
