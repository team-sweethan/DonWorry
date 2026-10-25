package com.donworry.identity.service;

import com.donworry.identity.entity.AdminChangeHistory;
import com.donworry.identity.repository.AdminChangeHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class AdminChangeHistoryService {
    private final AdminChangeHistoryRepository repository;
    private final JsonMapper mapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUser(Long actorId, Long targetId, String type, Object before, Object after) {
        repository.save(AdminChangeHistory.forUser(
                actorId,
                targetId,
                type,
                before == null ? null : mapper.writeValueAsString(before),
                mapper.writeValueAsString(after)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordMerchant(Long actorId, Long targetId, String type, Object before, Object after) {
        repository.save(AdminChangeHistory.forMerchant(
                actorId,
                targetId,
                type,
                before == null ? null : mapper.writeValueAsString(before),
                mapper.writeValueAsString(after)));
    }
}
