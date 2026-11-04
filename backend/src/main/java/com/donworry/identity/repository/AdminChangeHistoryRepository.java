package com.donworry.identity.repository;

import com.donworry.identity.entity.AdminChangeHistory;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface AdminChangeHistoryRepository extends Repository<AdminChangeHistory, Long> {
    AdminChangeHistory save(AdminChangeHistory history);

    Optional<AdminChangeHistory> findFirstByTargetUserIdAndChangeTypeOrderByCreatedAtDesc(
            Long targetUserId, String changeType);
}
