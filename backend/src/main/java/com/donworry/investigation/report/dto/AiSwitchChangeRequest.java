package com.donworry.investigation.report.dto;

// 끄기 · 다시 켜기 사유 (ADR-0083 결정 3) — 앞뒤 공백을 뺀 1~500자는 서비스가 확인한다
public record AiSwitchChangeRequest(String reason) {}
