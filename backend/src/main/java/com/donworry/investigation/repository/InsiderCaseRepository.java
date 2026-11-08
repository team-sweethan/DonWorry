package com.donworry.investigation.repository;

import com.donworry.investigation.entity.InsiderCase;
import com.donworry.investigation.entity.InsiderCaseStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 내부자 사건 (#670) — 조사 기록이라 삭제를 노출하지 않는다
public interface InsiderCaseRepository extends Repository<InsiderCase, Long> {
    InsiderCase save(InsiderCase insiderCase);

    Optional<InsiderCase> findById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM InsiderCase c WHERE c.id = :id")
    Optional<InsiderCase> findByIdForUpdate(@Param("id") Long id);

    Optional<InsiderCase> findByStaffIdAndStatus(Long staffId, InsiderCaseStatus status);

    boolean existsByStaffIdAndStatus(Long staffId, InsiderCaseStatus status);

    // 배정 후보에서 뺄 직원 — 열린 내부자 사건이 있는 직원
    @Query("""
            SELECT c.staffId FROM InsiderCase c
            WHERE c.status = com.donworry.investigation.entity.InsiderCaseStatus.OPEN
            """)
    Set<Long> findOpenStaffIds();

    List<InsiderCase> findTop100ByStatusOrderByUpdatedAtDescIdDesc(InsiderCaseStatus status);

    // 상세의 이 직원 지난 판정 — 최근부터
    List<InsiderCase> findTop20ByStaffIdAndIdNotOrderByOpenedAtDescIdDesc(Long staffId, Long excludedId);

    long countByStatus(InsiderCaseStatus status);

    // 내부자 관제 (#679) — 열린 사건은 사람당 1개라 직원 수보다 많지 않다
    List<InsiderCase> findByStatusOrderByIdAsc(InsiderCaseStatus status);
}
