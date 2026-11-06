package com.donworry.identity.api;

// 직원이 하려던 일. 값은 기록을 부르는 조각에서 더한다 (ADR-0082). DB는 CHECK 없는 VARCHAR라 값 추가에 migration이 필요 없다
public enum StaffAccessType {
    CASE_DETAIL_VIEW
}
