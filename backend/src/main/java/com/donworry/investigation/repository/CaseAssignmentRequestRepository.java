package com.donworry.investigation.repository;

import com.donworry.investigation.entity.AssignmentRequestStatus;
import com.donworry.investigation.entity.CaseAssignmentRequest;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 조사 기록이라 삭제를 노출하지 않는다
public interface CaseAssignmentRequestRepository extends Repository<CaseAssignmentRequest, Long> {

    CaseAssignmentRequest save(CaseAssignmentRequest request);

    Optional<CaseAssignmentRequest> findById(Long requestId);

    // 승인 · 거절 · 함께 승인 — 배정 잠금을 잡은 트랜잭션만 요청 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM CaseAssignmentRequest r WHERE r.id = :id")
    Optional<CaseAssignmentRequest> findByIdForUpdate(@Param("id") Long id);

    boolean existsByCaseIdAndStatus(Long caseId, AssignmentRequestStatus status);

    Optional<CaseAssignmentRequest> findByCaseIdAndStatus(Long caseId, AssignmentRequestStatus status);

    List<CaseAssignmentRequest> findByCaseIdInAndStatus(Collection<Long> caseIds, AssignmentRequestStatus status);

    List<CaseAssignmentRequest> findTop100ByStatusOrderByRequestedAtDescIdDesc(AssignmentRequestStatus status);

    long countByStatus(AssignmentRequestStatus status);
}
