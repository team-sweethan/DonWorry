package com.donworry.identity.repository;

import com.donworry.identity.entity.AdminChangeHistory;
import org.springframework.data.repository.Repository;

public interface AdminChangeHistoryRepository extends Repository<AdminChangeHistory, Long> {
    AdminChangeHistory save(AdminChangeHistory history);
}
