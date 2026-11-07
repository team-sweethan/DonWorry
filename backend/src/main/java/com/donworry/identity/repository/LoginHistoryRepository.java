package com.donworry.identity.repository;

import com.donworry.identity.entity.LoginEventType;
import com.donworry.identity.entity.LoginHistory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LoginHistoryRepository extends JpaRepository<LoginHistory, Long> {
    Page<LoginHistory> findByUserIdAndOccurredAtGreaterThanAndOccurredAtLessThanEqual(
            Long userId, LocalDateTime cutoff, LocalDateTime now, Pageable pageable);

    Optional<LoginHistory>
            findFirstByUserIdAndEventTypeAndOccurredAtGreaterThanAndOccurredAtLessThanEqualOrderByOccurredAtDescHistoryIdDesc(
                    Long userId, LoginEventType eventType, LocalDateTime cutoff, LocalDateTime now);

    boolean existsByLoginReferenceAndEventType(UUID reference, LoginEventType eventType);

    // 직원 로그인 기록 읽기 창구 (#643, StaffLoginReader) — 내부자 탐지 INS-07
    Optional<LoginHistory> findByLoginReferenceAndEventType(UUID reference, LoginEventType eventType);

    List<LoginHistory>
            findByUserIdAndEventTypeAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAscHistoryIdAsc(
                    Long userId, LoginEventType eventType, LocalDateTime from, LocalDateTime to);

    @Modifying
    @Query(
            value =
                    "DELETE FROM login_histories WHERE occurred_at <= CAST(:cutoff AS DATETIME(6)) ORDER BY occurred_at, history_id LIMIT 1000",
            nativeQuery = true)
    int deleteExpiredBatch(String cutoff);
}
