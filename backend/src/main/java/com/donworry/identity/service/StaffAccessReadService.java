package com.donworry.identity.service;

import com.donworry.identity.api.StaffAccessReader;
import com.donworry.identity.api.StaffAccessRecorded;
import com.donworry.identity.repository.StaffAccessEventRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 접근 기록 읽기 (#516) — P2 내부자 탐지 · 경보 근거용. 기록(StaffAccessService)과 빈을 나눈다:
// 기록 실패 테스트가 StaffAccessRecorder를 가짜로 바꿔도 읽기 창구가 함께 사라지지 않게
@Service
@RequiredArgsConstructor
public class StaffAccessReadService implements StaffAccessReader {
    private final StaffAccessEventRepository events;

    @Override
    @Transactional(readOnly = true)
    public List<StaffAccessRecorded> findRecent(Long staffId, Instant from, Instant to) {
        return events
                .findByStaffIdAndOccurredAtBetweenOrderByOccurredAtAscEventIdAsc(
                        staffId,
                        LocalDateTime.ofInstant(from, ZoneOffset.UTC),
                        LocalDateTime.ofInstant(to, ZoneOffset.UTC))
                .stream()
                .map(StaffAccessService::toRecorded)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffAccessRecorded> findByIds(Collection<Long> eventIds) {
        if (eventIds.isEmpty()) return List.of();
        return events.findByEventIdInOrderByOccurredAtAscEventIdAsc(eventIds).stream()
                .map(StaffAccessService::toRecorded)
                .toList();
    }
}
