package com.donworry.investigation.repository;

import com.donworry.investigation.entity.CaseHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseHistoryRepository extends JpaRepository<CaseHistory, Long> {

    List<CaseHistory> findBySecurityCaseIdOrderByCreatedAtAscIdAsc(Long caseId);
}
