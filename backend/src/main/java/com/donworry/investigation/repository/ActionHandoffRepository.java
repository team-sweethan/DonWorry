package com.donworry.investigation.repository;

import com.donworry.investigation.entity.ActionHandoff;
import com.donworry.investigation.entity.HandoffStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 조사 기록의 물리 삭제를 노출하지 않고 필요한 저장·조회만 제공한다
public interface ActionHandoffRepository extends Repository<ActionHandoff, Long> {

    ActionHandoff save(ActionHandoff handoff);

    Optional<ActionHandoff> findById(Long handoffId);

    List<ActionHandoff> findByDecisionCasePaymentSecurityCaseId(Long caseId);

    // P5 접수 결과 이벤트는 멱등키로 인계 행을 찾는다
    // 쓰기 잠금 — 재시도와 접수 확인이 같은 행을 동시에 바꿔 ACCEPTED를 덮어쓰지 않게 한다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ActionHandoff> findByIdempotencyKey(UUID idempotencyKey);

    // 재시도 직전에 행을 잠그고 다시 읽는다 — 그 사이 접수됐으면 retry()가 거부한다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM ActionHandoff h WHERE h.id = :id")
    Optional<ActionHandoff> findByIdForUpdate(@Param("id") Long id);

    // 재시도 대상 — idx_action_handoffs_retry(status, last_attempted_at). 잠그지 않으므로 바꾸기 전 findByIdForUpdate로 다시 읽는다
    List<ActionHandoff> findByStatusAndLastAttemptedAtBefore(HandoffStatus status, Instant before);
}
