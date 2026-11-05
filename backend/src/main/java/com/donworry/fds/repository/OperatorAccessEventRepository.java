package com.donworry.fds.repository;

import com.donworry.fds.entity.OperatorAccessDeniedType;
import com.donworry.fds.entity.OperatorAccessEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OperatorAccessEventRepository extends JpaRepository<OperatorAccessEvent, Long> {
    Optional<OperatorAccessEvent> findByIdentityEventId(Long identityEventId);

    List<OperatorAccessEvent> findByUserIdAndDeniedTypeAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(
            Long userId, OperatorAccessDeniedType deniedType, Instant occurredAt);

    @Modifying
    @Query(value = """
                    UPDATE fds_operator_access_events
                    SET client_ip = NULL
                    WHERE client_ip IS NOT NULL
                      AND occurred_at < CAST(:cutoff AS DATETIME(6))
                    ORDER BY occurred_at, operator_access_event_id
                    LIMIT 1000
                    """, nativeQuery = true)
    int clearExpiredClientIpBatch(String cutoff);
}
