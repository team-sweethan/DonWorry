package com.donworry.identity.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

// 로그인 보안 대시보드 읽기 창구 (#770, ADR-0082) — P3 대시보드가 쓴다. login_histories를 읽기만 한다
// 시도 = 로그인 성공 + 실패(로그아웃 제외). IP · 시도한 아이디 원문은 내보내지 않는다(없는 아이디는 저장도 안 됨)
public interface LoginTrafficReader {
    // 경계 기준 — 탐지와 같은 값
    LoginAttackCriteria criteria();

    // (from, to] 전체 시도 · 실패 수 — 탐지와 같은 구간 경계
    LoginTrafficCount count(Instant from, Instant to);

    // [from, to)를 bucket 길이로 나눈 칸마다 시도 · 실패 수, 시각 오름차순. 시도가 없던 칸은 내보내지 않는다
    List<LoginTrafficBucket> countPerBucket(Instant from, Instant to, Duration bucket);

    // (from, to] 로그인 시도(모든 계정 · 없는 아이디 포함), 최근 것부터 limit개
    List<LoginAttempt> findRecentAttempts(Instant from, Instant to, int limit);
}
