package com.donworry.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// 직원 로그인 기록 읽기 창구 (#643, ADR-0082) — P2 내부자 탐지 INS-07이 "처음 보는 브라우저인가"를 볼 때 쓴다
// 성공 로그인만 내보낸다. 없는 아이디로 실패한 기록은 직원 번호가 없어 원래 대상이 아니다
public interface StaffLoginReader {
    // 로그인 번호(세션)로 그 로그인 성공 기록
    Optional<StaffLogin> findLogin(UUID loginReference);

    // 한 직원의 [from, to) 성공 로그인, 시각 · 기록 번호 오름차순
    List<StaffLogin> findSuccessfulLogins(Long staffId, Instant from, Instant to);

    // 한 직원의 [from, to] 로그인 성공 · 실패 · 로그아웃, 시각 · 기록 번호 오름차순 (#679 내부자 관제)
    List<StaffLoginEvent> findLoginEvents(Long staffId, Instant from, Instant to);
}
