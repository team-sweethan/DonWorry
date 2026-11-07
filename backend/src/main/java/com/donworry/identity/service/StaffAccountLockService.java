package com.donworry.identity.service;

import com.donworry.identity.api.StaffAccountLockException;
import com.donworry.identity.api.StaffAccountLockException.Reason;
import com.donworry.identity.api.StaffAccountLocker;
import com.donworry.identity.api.StaffLockOutcome;
import com.donworry.identity.api.StaffLockReason;
import com.donworry.identity.api.StaffLockRecord;
import com.donworry.identity.api.StaffLockRelease;
import com.donworry.identity.entity.PlatformUser;
import com.donworry.identity.entity.StaffAccountLock;
import com.donworry.identity.entity.StaffAccountLockStatus;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.entity.UserStatus;
import com.donworry.identity.repository.PlatformUserRepository;
import com.donworry.identity.repository.StaffAccountLockRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

// 보안 직원 계정 보안 정지 · 해제 (#647, ADR-0084)
// 정지는 사장님 정지(OperatorAccountSuspensionService, ADR-0078)와 같은 순서:
// 계정 순서 잠금 → 새 트랜잭션(정지 기록 · SUSPENDED · 변경 이력) 커밋 → 세션 만료. 차단 응답이 롤백돼도 정지는 남는다
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffAccountLockService implements StaffAccountLocker {
    private final PlatformUserRepository users;
    private final StaffAccountLockRepository lockRecords;
    private final AdminChangeHistoryService histories;
    private final AccountLockService locks;
    private final UserSessionService sessions;
    private final PlatformTransactionManager transactionManager;
    private final Clock identityClock;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void lockBySystem(Long staffId, StaffLockReason reason, Long sourceAlertId) {
        lock(staffId, null, reason, sourceAlertId);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void lockByAdmin(Long staffId, Long adminId, StaffLockReason reason, Long sourceAlertId) {
        requirePositive(adminId, "adminId");
        lock(staffId, adminId, reason, sourceAlertId);
    }

    private void lock(Long staffId, Long adminId, StaffLockReason reason, Long sourceAlertId) {
        requirePositive(staffId, "staffId");
        Assert.notNull(reason, "reason");
        String loginId = find(staffId).getLoginId();
        locks.withAccount(loginId, () -> {
            var transaction = new TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            boolean locked = Boolean.TRUE.equals(transaction.execute(status -> {
                var user = find(staffId);
                if (user.getRole() != UserRole.SECURITY_STAFF)
                    throw new StaffAccountLockException(Reason.NOT_SECURITY_STAFF);
                if (adminId != null) requireAdmin(adminId);
                // 이미 정지 중이면 기록을 두 줄 만들지 않는다
                if (lockRecords.existsByStaffIdAndStatus(staffId, StaffAccountLockStatus.OPEN)) return false;
                UserStatus before = user.getStatus();
                Instant now = identityClock.instant();
                lockRecords.saveAndFlush(
                        adminId == null
                                ? StaffAccountLock.bySystem(staffId, reason, sourceAlertId, now)
                                : StaffAccountLock.byAdmin(staffId, adminId, reason, sourceAlertId, now));
                // 이미 비활성(퇴사)인 직원은 비활성 그대로 두고 기록만 남긴다 — 해제 때 사용 중으로 되살아나지 않게
                if (before == UserStatus.ACTIVE) user.changeStatus(UserStatus.SUSPENDED);
                var after = new LinkedHashMap<String, Object>();
                after.put("status", user.getStatus().name());
                after.put("reason", reason.name());
                if (sourceAlertId != null) after.put("alertId", sourceAlertId);
                var previous = Map.of("status", before.name());
                if (adminId == null) histories.recordSystemUser(staffId, "USER_SUSPENDED_BY_INSIDER", previous, after);
                else histories.recordUser(adminId, staffId, "USER_SUSPENDED_BY_INSIDER", previous, after);
                return true;
            }));
            // 정지가 커밋된 뒤에만 세션을 끊는다 — 끊긴 직후 다시 로그인해도 SUSPENDED로 막힌다
            if (locked) {
                sessions.expireAll(staffId);
                log.info("보안 직원 계정 보안 정지 staffId={} reason={} alertId={}", staffId, reason, sourceAlertId);
            }
            return null;
        });
    }

    @Override
    @Transactional
    public void release(StaffLockRelease release) {
        Assert.notNull(release, "release");
        requirePositive(release.staffId(), "staffId");
        requirePositive(release.adminId(), "adminId");
        Assert.notNull(release.outcome(), "outcome");
        requireAdmin(release.adminId());
        var open = lockRecords.findByStaffIdAndStatus(release.staffId(), StaffAccountLockStatus.OPEN);
        if (open.isEmpty()) return;
        var user = find(release.staffId());
        UserStatus before = user.getStatus();
        open.get()
                .release(
                        release.adminId(),
                        release.caseId(),
                        release.decision(),
                        release.outcome(),
                        identityClock.instant());
        // 보안 정지 중일 때만 상태를 바꾼다 — 정지 전부터 비활성이던 계정을 되살리지 않는다
        if (before == UserStatus.SUSPENDED)
            user.changeStatus(release.outcome() == StaffLockOutcome.RESTORE ? UserStatus.ACTIVE : UserStatus.INACTIVE);
        var after = new LinkedHashMap<String, Object>();
        after.put("status", user.getStatus().name());
        after.put("outcome", release.outcome().name());
        if (release.caseId() != null) after.put("caseId", release.caseId());
        if (release.decision() != null) after.put("decision", release.decision());
        histories.recordUser(
                release.adminId(),
                release.staffId(),
                "USER_RELEASED_BY_INSIDER_DECISION",
                Map.of("status", before.name()),
                after);
        log.info(
                "보안 직원 계정 보안 정지 해제 staffId={} adminId={} caseId={} outcome={}",
                release.staffId(),
                release.adminId(),
                release.caseId(),
                release.outcome());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isLocked(Long staffId) {
        requirePositive(staffId, "staffId");
        return lockRecords.existsByStaffIdAndStatus(staffId, StaffAccountLockStatus.OPEN);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffLockRecord> findLocks(Long staffId) {
        requirePositive(staffId, "staffId");
        return lockRecords.findByStaffIdOrderByLockedAtDescLockIdDesc(staffId).stream()
                .map(StaffAccountLockService::record)
                .toList();
    }

    private static StaffLockRecord record(StaffAccountLock lock) {
        return StaffLockRecord.builder()
                .lockId(lock.getLockId())
                .staffId(lock.getStaffId())
                .reason(lock.getReasonCode())
                .sourceAlertId(lock.getSourceAlertId())
                .lockedBy(lock.getLockedBy())
                .lockedAt(instant(lock.getLockedAt()))
                .open(lock.isOpen())
                .releasedAt(instant(lock.getReleasedAt()))
                .releasedBy(lock.getReleasedBy())
                .releaseCaseId(lock.getReleaseCaseId())
                .releaseDecision(lock.getReleaseDecision())
                .releaseOutcome(lock.getReleaseOutcome())
                .build();
    }

    private static Instant instant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }

    private void requireAdmin(Long adminId) {
        if (find(adminId).getRole() != UserRole.ADMIN) throw new StaffAccountLockException(Reason.NOT_ADMIN);
    }

    private static void requirePositive(Long id, String name) {
        if (id == null || id <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private PlatformUser find(Long userId) {
        return users.findById(userId).orElseThrow(() -> new StaffAccountLockException(Reason.USER_NOT_FOUND));
    }
}
