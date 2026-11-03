package com.donworry.investigation.repository;

import com.donworry.investigation.entity.CaseHistory;
import com.donworry.investigation.entity.CaseHistoryEventType;
import java.util.List;
import org.springframework.data.repository.Repository;

// 감사 이력은 INSERT 전용이다 — 물리 삭제를 노출하지 않고 저장·조회만 제공한다
public interface CaseHistoryRepository extends Repository<CaseHistory, Long> {

    CaseHistory save(CaseHistory history);

    List<CaseHistory> findBySecurityCaseIdOrderByCreatedAtAscIdAsc(Long caseId);

    // 고객 취소 반영(#382)처럼 특정 종류의 이력만 읽는다
    List<CaseHistory> findBySecurityCaseIdAndEventType(Long caseId, CaseHistoryEventType eventType);

    // 여러 Case에 걸친 특정 이력 — 조치 내역의 고객 취소 표시(#439)
    List<CaseHistory> findByEventType(CaseHistoryEventType eventType);
}
