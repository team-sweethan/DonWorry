package com.donworry.payment.service;

// 담당 사건 범위 밖 고객(또는 고객 조건 없음)의 내보내기 (#730, ADR-0082 3.1) — 거부 기록은 이미 남겼다
public class ExportOutOfScopeException extends RuntimeException {
    public ExportOutOfScopeException() {
        super("export out of case scope");
    }
}
