package com.donworry.identity.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

// 접근 기록 읽기 창구 (#516, ADR-0082) — P2 내부자 탐지 · 경보 근거 화면용. 기록에는 원래 원문 칸이 없다
// 한 줄 모양은 저장 알림(StaffAccessRecorded)과 같다
public interface StaffAccessReader {

    // 한 직원의 [from, to] 기록, 시각 · 기록 번호 오름차순
    List<StaffAccessRecorded> findRecent(Long staffId, Instant from, Instant to);

    // 기록 번호로 찾기, 시각 · 기록 번호 오름차순
    List<StaffAccessRecorded> findByIds(Collection<Long> eventIds);
}
