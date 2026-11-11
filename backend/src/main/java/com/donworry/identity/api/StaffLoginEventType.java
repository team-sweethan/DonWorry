package com.donworry.identity.api;

// 로그인 기록 종류 (#679) — identity 엔티티 enum(LoginEventType)을 다른 모듈에 내보내지 않으려고 따로 둔다
public enum StaffLoginEventType {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGOUT
}
