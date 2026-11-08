package com.donworry.fds.repository;

import com.donworry.fds.api.InsiderAlertSeverity;
import com.donworry.fds.api.InsiderRule;
import com.donworry.fds.entity.InsiderAlert;
import com.donworry.fds.entity.InsiderAlertStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 내부자 경보 (#516) — 물리 삭제 없음. 직원별 줄 세우기 행(insider_detection_states)도 여기서 잡는다
public interface InsiderAlertRepository extends Repository<InsiderAlert, Long> {
    InsiderAlert save(InsiderAlert alert);

    Optional<InsiderAlert> findById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM InsiderAlert a WHERE a.id = :id")
    Optional<InsiderAlert> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT a.staffId FROM InsiderAlert a WHERE a.id = :id")
    Optional<Long> findStaffIdById(@Param("id") Long id);

    Optional<InsiderAlert> findByStaffIdAndRuleCodeAndStatus(
            Long staffId, InsiderRule ruleCode, InsiderAlertStatus status);

    List<InsiderAlert> findByStaffIdAndStatus(Long staffId, InsiderAlertStatus status);

    boolean existsByStaffIdAndStatus(Long staffId, InsiderAlertStatus status);

    // 내보내기 문지기 (#651) — 이 등급들 중 하나로 열린 경보가 있는가
    boolean existsByStaffIdAndStatusAndSeverityIn(
            Long staffId, InsiderAlertStatus status, Collection<InsiderAlertSeverity> severities);

    List<InsiderAlert> findTop100ByStatusOrderByUpdatedAtDescIdDesc(InsiderAlertStatus status);

    long countByStatus(InsiderAlertStatus status);

    // 이 직원 · 규칙의 마지막 닫힘 시각 — 그 전 기록은 다시 세지 않는다. 닫힌 적 없으면 null
    @Query("""
            SELECT MAX(a.closedAt) FROM InsiderAlert a
            WHERE a.staffId = :staffId AND a.ruleCode = :rule
              AND a.status = com.donworry.fds.entity.InsiderAlertStatus.CLOSED
            """)
    Instant findLastClosedAt(@Param("staffId") Long staffId, @Param("rule") InsiderRule rule);

    // 직원별 줄 세우기 — 처음 보는 직원이면 행을 만들고, 있으면 그 행을 고쳐 배타 잠금을 잡는다. 트랜잭션 끝까지 같은 직원은 기다린다
    // INSERT IGNORE + SELECT FOR UPDATE는 중복 키에서 공유 잠금을 먼저 잡아, 여럿이 동시에 배타 잠금으로 올리려다 교착이 난다
    @Modifying
    @Query(
            value = "INSERT INTO insider_detection_states (staff_id, updated_at) VALUES (:staffId, UTC_TIMESTAMP(6))"
                    + " ON DUPLICATE KEY UPDATE updated_at = UTC_TIMESTAMP(6)",
            nativeQuery = true)
    int lockDetectionState(@Param("staffId") Long staffId);
}
