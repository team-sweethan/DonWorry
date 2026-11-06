package com.donworry.investigation.entity;

// 담당 변경 사유 (#608). 내부자 조사 대상처럼 ADMIN만 고르는 사유는 그 조각(조각 8)에서 추가한다
public enum AssignmentChangeReason {
    CONFLICT_OF_INTEREST,
    OTHER
}
