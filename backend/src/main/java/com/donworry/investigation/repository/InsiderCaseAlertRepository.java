package com.donworry.investigation.repository;

import com.donworry.investigation.entity.InsiderCaseAlert;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 내부자 사건에 묶인 경보 (#670) — 조사 기록이라 삭제를 노출하지 않는다
public interface InsiderCaseAlertRepository extends Repository<InsiderCaseAlert, Long> {
    InsiderCaseAlert save(InsiderCaseAlert link);

    Optional<InsiderCaseAlert> findById(Long alertId);

    // 여러 경보가 각각 묶인 사건 (#777 로그인 보안 대시보드 — 직원의 지금 열린 사건이 아니라 경보가 실제로 속한 사건)
    List<InsiderCaseAlert> findByAlertIdIn(Collection<Long> alertIds);

    List<InsiderCaseAlert> findByInsiderCaseIdOrderByRaisedAtAscAlertIdAsc(Long insiderCaseId);

    // 판정 때 계정 정지 근거로 남길 가장 최근 경보
    Optional<InsiderCaseAlert> findFirstByInsiderCaseIdOrderByRaisedAtDescAlertIdDesc(Long insiderCaseId);

    @Query("""
            SELECT a.insiderCaseId AS insiderCaseId, COUNT(a) AS alertCount FROM InsiderCaseAlert a
            WHERE a.insiderCaseId IN :caseIds
            GROUP BY a.insiderCaseId
            """)
    List<CaseAlertCount> countByCaseIds(@Param("caseIds") Collection<Long> caseIds);

    interface CaseAlertCount {
        Long getInsiderCaseId();

        Long getAlertCount();
    }
}
