package com.donworry.fds.repository;

import com.donworry.fds.entity.FdsRulePolicyChangeHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FdsRulePolicyChangeHistoryRepository extends JpaRepository<FdsRulePolicyChangeHistory, Long> {
    Page<FdsRulePolicyChangeHistory> findAllByOrderByChangedAtDesc(Pageable pageable);

    @Query("""
            select history from FdsRulePolicyChangeHistory history
            where :search = ''
               or lower(history.ruleCode) like lower(concat('%', :search, '%'))
               or lower(coalesce(history.requestedByName, '')) like lower(concat('%', :search, '%'))
               or lower(history.changedByName) like lower(concat('%', :search, '%'))
            """)
    Page<FdsRulePolicyChangeHistory> search(@Param("search") String search, Pageable pageable);
}
