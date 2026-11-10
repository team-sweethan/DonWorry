package com.donworry.identity.repository;

import com.donworry.identity.entity.LoginEventType;
import com.donworry.identity.entity.LoginHistory;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    // 내부자 관제 타임라인 (#679, StaffLoginReader.findLoginEvents) — 종류 구분 없이 [from, to]
    List<LoginHistory> findByUserIdAndOccurredAtBetweenOrderByOccurredAtAscHistoryIdAsc(
            Long userId, LocalDateTime from, LocalDateTime to);

    // 로그인 공격 경계 (#770, LoginAttackCheck) — (from, to] 전체 계정 시도 · 실패 수. occurred_at 색인(idx_login_histories_retention)으로
    // 구간을 찾는다
    long countByEventTypeInAndOccurredAtGreaterThanAndOccurredAtLessThanEqual(
            Collection<LoginEventType> eventTypes, LocalDateTime from, LocalDateTime to);

    long countByEventTypeAndOccurredAtGreaterThanAndOccurredAtLessThanEqual(
            LoginEventType eventType, LocalDateTime from, LocalDateTime to);

    // 로그인 보안 대시보드 (#770, LoginTrafficReader) — (from, to] 없는 아이디로 실패한 수
    long countByUserIdIsNullAndEventTypeAndOccurredAtGreaterThanAndOccurredAtLessThanEqual(
            LoginEventType eventType, LocalDateTime from, LocalDateTime to);

    // [from, to) 분마다 시도(성공 + 실패) · 실패 · 없는 아이디 실패 수. 시각은 UTC 문자열로 받아 접속 시간대 변환을 피한다
    @Query(
            value = "SELECT DATE_FORMAT(occurred_at, '%Y-%m-%d %H:%i:00') AS minuteStart,"
                    + " COUNT(*) AS attempts,"
                    + " CAST(SUM(event_type = 'LOGIN_FAILURE') AS SIGNED) AS failures,"
                    + " CAST(SUM(event_type = 'LOGIN_FAILURE' AND user_id IS NULL) AS SIGNED) AS unknownAccountFailures"
                    + " FROM login_histories"
                    + " WHERE occurred_at >= CAST(:from AS DATETIME(6)) AND occurred_at < CAST(:to AS DATETIME(6))"
                    + " AND event_type IN ('LOGIN_SUCCESS', 'LOGIN_FAILURE')"
                    + " GROUP BY minuteStart ORDER BY minuteStart",
            nativeQuery = true)
    List<LoginMinuteCount> countPerMinute(@Param("from") String from, @Param("to") String to);

    interface LoginMinuteCount {
        String getMinuteStart();

        Long getAttempts();

        Long getFailures();

        Long getUnknownAccountFailures();
    }

    // (from, to] 보안 직원 로그인 성공 · 실패, 최근 것부터 (Pageable로 개수 제한)
    @Query("""
            SELECT h FROM LoginHistory h, PlatformUser u
            WHERE u.userId = h.userId
              AND u.role = com.donworry.identity.entity.UserRole.SECURITY_STAFF
              AND h.eventType IN (com.donworry.identity.entity.LoginEventType.LOGIN_SUCCESS,
                                  com.donworry.identity.entity.LoginEventType.LOGIN_FAILURE)
              AND h.occurredAt > :from AND h.occurredAt <= :to
            ORDER BY h.occurredAt DESC, h.historyId DESC
            """)
    List<LoginHistory> findSecurityStaffLogins(
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to, Pageable pageable);

    @Modifying
    @Query(
            value =
                    "DELETE FROM login_histories WHERE occurred_at <= CAST(:cutoff AS DATETIME(6)) ORDER BY occurred_at, history_id LIMIT 1000",
            nativeQuery = true)
    int deleteExpiredBatch(String cutoff);
}
