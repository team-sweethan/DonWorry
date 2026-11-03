package com.donworry.settlement.entity;

public enum SettlementStatus {
    SCHEDULED, // 정산 예정 (매입 완료 후 정산금 계산, 확정 조건 대기)
    COMPLETED, // 정산 완료 (픽업 후 72시간 경과 및 정산 완료 배치로 확정)
    CANCELED // 정산 취소 (정산 확정 전 결제 전체취소 성공)
}
