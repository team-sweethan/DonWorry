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

    // [from, to)를 bucketMicros 길이로 나눈 칸마다 시도(성공 + 실패) · 실패 · 없는 아이디 실패 수. bucket = from부터 몇 번째 칸인지
    // 시각은 UTC 문자열로 받아 접속 시간대 변환을 피한다
    @Query(
            value =
                    "SELECT CAST(FLOOR(TIMESTAMPDIFF(MICROSECOND, CAST(:from AS DATETIME(6)), occurred_at) / :bucketMicros)"
                            + " AS SIGNED) AS bucket,"
                            + " COUNT(*) AS attempts,"
                            + " CAST(SUM(event_type = 'LOGIN_FAILURE') AS SIGNED) AS failures,"
                            + " CAST(SUM(event_type = 'LOGIN_FAILURE' AND user_id IS NULL) AS SIGNED) AS unknownAccountFailures"
                            + " FROM login_histories"
                            + " WHERE occurred_at >= CAST(:from AS DATETIME(6)) AND occurred_at < CAST(:to AS DATETIME(6))"
                            + " AND event_type IN ('LOGIN_SUCCESS', 'LOGIN_FAILURE')"
                            + " GROUP BY bucket ORDER BY bucket",
            nativeQuery = true)
    List<LoginBucketCount> countPerBucket(
            @Param("from") String from, @Param("to") String to, @Param("bucketMicros") long bucketMicros);

    interface LoginBucketCount {
        Long getBucket();

        Long getAttempts();

        Long getFailures();

        Long getUnknownAccountFailures();
    }

    // (from, to] 로그인 성공 · 실패와 그 계정의 역할(없는 아이디면 null), 최근 것부터 (Pageable로 개수 제한)
    @Query("""
            SELECT h, u.role FROM LoginHistory h LEFT JOIN PlatformUser u ON u.userId = h.userId
            WHERE h.eventType IN (com.donworry.identity.entity.LoginEventType.LOGIN_SUCCESS,
                                  com.donworry.identity.entity.LoginEventType.LOGIN_FAILURE)
              AND h.occurredAt > :from AND h.occurredAt <= :to
            ORDER BY h.occurredAt DESC, h.historyId DESC
            """)
    List<Object[]> findRecentAttempts(
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to, Pageable pageable);

    @Modifying
    @Query(
            value =
                    "DELETE FROM login_histories WHERE occurred_at <= CAST(:cutoff AS DATETIME(6)) ORDER BY occurred_at, history_id LIMIT 1000",
            nativeQuery = true)
    int deleteExpiredBatch(String cutoff);
}
