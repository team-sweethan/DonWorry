package com.donworry.identity.service;

import com.donworry.identity.api.OperatorAccountSuspender;
import com.donworry.identity.api.OperatorAccountSuspensionException;
import com.donworry.identity.api.OperatorAccountSuspensionException.Reason;
import com.donworry.identity.entity.PlatformUser;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.entity.UserStatus;
import com.donworry.identity.repository.PlatformUserRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

@Service
@RequiredArgsConstructor
public class OperatorAccountSuspensionService implements OperatorAccountSuspender {
    private final PlatformUserRepository users;
    private final AdminChangeHistoryService histories;
    private final AccountLockService locks;
    private final UserSessionService sessions;
    private final PlatformTransactionManager transactionManager;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void suspend(Long userId) {
        Assert.isTrue(userId != null && userId > 0, "userId must be positive");
        String loginId = find(userId).getLoginId();
        locks.withAccount(loginId, () -> {
            var transaction = new TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            boolean changed = Boolean.TRUE.equals(transaction.execute(status -> {
                var user = find(userId);
                if (user.getRole() != UserRole.OPERATOR)
                    throw new OperatorAccountSuspensionException(Reason.NOT_OPERATOR);
                if (user.getStatus() == UserStatus.INACTIVE) return false;
                user.changeStatus(UserStatus.INACTIVE);
                histories.recordSystemUser(
                        userId, "USER_SUSPENDED_BY_FDS", Map.of("status", "ACTIVE"), Map.of("status", "INACTIVE"));
                return true;
            }));
            // 관리자 해제·새 로그인은 상태 커밋과 세션 만료가 모두 끝난 뒤 진행한다.
            if (changed) sessions.expireAll(userId);
            return null;
        });
    }

    private PlatformUser find(Long userId) {
        return users.findById(userId).orElseThrow(() -> new OperatorAccountSuspensionException(Reason.USER_NOT_FOUND));
    }
}
