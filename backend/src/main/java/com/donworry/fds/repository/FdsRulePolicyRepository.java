package com.donworry.fds.repository;

import com.donworry.fds.entity.FdsRulePolicy;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FdsRulePolicyRepository extends JpaRepository<FdsRulePolicy, Long> {
    Optional<FdsRulePolicy> findByRuleCode(String ruleCode);

    List<FdsRulePolicy> findByRuleCodeIn(Collection<String> ruleCodes);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select policy from FdsRulePolicy policy where policy.ruleCode = :ruleCode")
    Optional<FdsRulePolicy> findByRuleCodeForUpdate(@Param("ruleCode") String ruleCode);
}
