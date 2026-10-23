package com.donworry.investigation.repository;

import com.donworry.investigation.entity.CaseHistory;
import java.util.List;
import org.springframework.data.repository.Repository;

// 감사 이력은 INSERT 전용이다 — 물리 삭제를 노출하지 않고 저장·조회만 제공한다
public interface CaseHistoryRepository extends Repository<CaseHistory, Long> {

    CaseHistory save(CaseHistory history);

    List<CaseHistory> findBySecurityCaseIdOrderByCreatedAtAscIdAsc(Long caseId);
}
