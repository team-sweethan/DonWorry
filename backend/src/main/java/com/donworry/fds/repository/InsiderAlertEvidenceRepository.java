package com.donworry.fds.repository;

import com.donworry.fds.entity.InsiderAlertEvidence;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 경보 근거 (#516) — INSERT 전용
public interface InsiderAlertEvidenceRepository extends Repository<InsiderAlertEvidence, Long> {
    InsiderAlertEvidence save(InsiderAlertEvidence evidence);

    @Query("SELECT e.accessEventId FROM InsiderAlertEvidence e WHERE e.alertId = :alertId ORDER BY e.accessEventId")
    List<Long> findAccessEventIds(@Param("alertId") Long alertId);

    @Query("SELECT DISTINCT e.accessEventId FROM InsiderAlertEvidence e WHERE e.alertId IN :alertIds")
    List<Long> findAccessEventIdsOf(@Param("alertIds") Collection<Long> alertIds);

    long countByAlertId(Long alertId);
}
