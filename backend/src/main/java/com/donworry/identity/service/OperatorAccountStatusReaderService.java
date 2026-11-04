package com.donworry.identity.service;

import com.donworry.identity.api.OperatorAccountStatus;
import com.donworry.identity.api.OperatorAccountStatusReader;
import com.donworry.identity.api.OperatorAccountStatusSnapshot;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.repository.AdminChangeHistoryRepository;
import com.donworry.identity.repository.PlatformUserRepository;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OperatorAccountStatusReaderService implements OperatorAccountStatusReader {
    private final PlatformUserRepository users;
    private final AdminChangeHistoryRepository histories;

    @Override
    // P2 집계 트랜잭션의 오래된 스냅샷을 재사용하지 않고 상태와 해제 이력을 함께 읽는다.
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public Optional<OperatorAccountStatusSnapshot> findById(Long userId) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("userId must be positive");
        return users.findById(userId)
                .filter(user -> user.getRole() == UserRole.OPERATOR)
                .map(user -> {
                    var lastActivatedAt = histories
                            .findFirstByTargetUserIdAndChangeTypeOrderByCreatedAtDesc(userId, "USER_ENABLED")
                            .map(history -> history.getCreatedAt().toInstant(ZoneOffset.UTC))
                            .orElse(null);
                    var status = switch (user.getStatus()) {
                        case ACTIVE -> OperatorAccountStatus.ACTIVE;
                        case INACTIVE -> OperatorAccountStatus.INACTIVE;
                    };
                    return new OperatorAccountStatusSnapshot(status, lastActivatedAt);
                });
    }
}
