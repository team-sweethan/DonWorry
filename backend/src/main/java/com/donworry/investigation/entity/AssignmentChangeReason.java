package com.donworry.investigation.entity;

// 담당 변경 사유 (#608). OTHER만 메모가 필수다. 개인 사유도 내용은 적지 않아도 된다 — 메모에 개인정보를 쓰지 않게
// 내부자 조사 대상처럼 ADMIN만 고르는 사유는 그 조각(조각 8)에서 추가한다
public enum AssignmentChangeReason {
    CONFLICT_OF_INTEREST,
    WORKLOAD,
    PERSONAL,
    OTHER
}
