package com.donworry.investigation.repository;

import com.donworry.investigation.entity.ActionHandoff;
import com.donworry.investigation.entity.HandoffStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActionHandoffRepository extends JpaRepository<ActionHandoff, Long> {

    List<ActionHandoff> findByDecisionCasePaymentSecurityCaseId(Long caseId);

    // P5 접수 결과 이벤트는 멱등키로 인계 행을 찾는다
    Optional<ActionHandoff> findByIdempotencyKey(UUID idempotencyKey);

    // 재시도 대상 — idx_action_handoffs_retry(status, last_attempted_at)
    List<ActionHandoff> findByStatusAndLastAttemptedAtBefore(HandoffStatus status, Instant before);
}
