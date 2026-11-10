package com.donworry.identity.api;

// 한 구간의 전체 로그인 시도 · 실패 수 (#770). 계정 구분 없음. unknownAccountFailures = 없는 아이디로 실패한 수(실패에 포함)
public record LoginTrafficCount(long attempts, long failures, long unknownAccountFailures) {}
