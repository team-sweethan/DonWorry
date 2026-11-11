package com.donworry.identity.repository;

import com.donworry.identity.entity.StaffAccessEvent;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.Repository;

// 접근 기록은 쌓기만 한다. 삭제 · 수정 메서드를 열지 않는다
// 읽기는 P2 내부자 탐지 · 경보 근거용 (#516) — 직원별 조회는 idx_staff_access_events_staff_time을 탄다
public interface StaffAccessEventRepository extends Repository<StaffAccessEvent, Long> {
    StaffAccessEvent saveAndFlush(StaffAccessEvent event);

    List<StaffAccessEvent> findByStaffIdAndOccurredAtBetweenOrderByOccurredAtAscEventIdAsc(
            Long staffId, LocalDateTime from, LocalDateTime to);

    List<StaffAccessEvent> findByEventIdInOrderByOccurredAtAscEventIdAsc(Collection<Long> eventIds);
}
