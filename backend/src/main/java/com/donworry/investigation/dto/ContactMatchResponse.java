package com.donworry.investigation.dto;

// 대조 결과 — 일치 여부만. 저장된 번호는 돌려주지 않는다 (#619)
public record ContactMatchResponse(boolean matched) {}
