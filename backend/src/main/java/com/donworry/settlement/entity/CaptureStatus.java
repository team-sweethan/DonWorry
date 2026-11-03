package com.donworry.settlement.entity;

public enum CaptureStatus {
    PROCESSING, // 매입 처리 중 (P6 내부 매입 로직 실행 중)
    COMPLETED, // 매입 완료 (매입 성공 확정)
    FAILED // 매입 실패 (매입 처리 실패)
}
