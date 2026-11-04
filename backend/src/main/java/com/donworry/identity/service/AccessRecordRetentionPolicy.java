package com.donworry.identity.service;

import java.time.Period;

// 보관·최근 성공 조회는 같은 달력상 1년을 쓰고, 목록 표시 범위는 따로 유지한다.
final class AccessRecordRetentionPolicy {
    static final Period RETENTION_PERIOD = Period.ofYears(1);
    static final Period LOGIN_LIST_PERIOD = Period.ofDays(90);

    private AccessRecordRetentionPolicy() {}
}
