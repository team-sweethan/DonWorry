package com.donworry.fds.repository;

import com.donworry.fds.entity.FdsRuleChangeRequest;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FdsRuleChangeRequestRepository extends JpaRepository<FdsRuleChangeRequest, Long> {
    boolean existsByRuleCodeAndStatus(String ruleCode, FdsRuleChangeRequest.Status status);

    Optional<FdsRuleChangeRequest> findByRuleCodeAndRequestedByUserIdAndStatus(
            String ruleCode, Long requestedByUserId, FdsRuleChangeRequest.Status status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from FdsRuleChangeRequest request where request.ruleChangeRequestId = :requestId")
    Optional<FdsRuleChangeRequest> findByIdForUpdate(@Param("requestId") Long requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<FdsRuleChangeRequest> findByRuleCodeAndStatus(String ruleCode, FdsRuleChangeRequest.Status status);

    Page<FdsRuleChangeRequest> findByStatusOrderByRequestedAtDesc(
            FdsRuleChangeRequest.Status status, Pageable pageable);
}
