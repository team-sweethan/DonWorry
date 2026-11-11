package com.donworry.identity.repository;

import com.donworry.identity.entity.StaffAccountLock;
import com.donworry.identity.entity.StaffAccountLockStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.Repository;

// 정지 기록은 지우지 않는다(조사 기록). 삭제 메서드를 열지 않는다
public interface StaffAccountLockRepository extends Repository<StaffAccountLock, Long> {
    StaffAccountLock saveAndFlush(StaffAccountLock lock);

    // 해제용 잠금 읽기(SELECT ... FOR UPDATE) — 같은 정지를 동시에 풀면 뒤 요청은 앞 판정이 끝날 때까지 기다렸다가
    // 이미 풀린 기록을 보고 아무것도 바꾸지 않는다. 잠금은 부른 쪽(판정) 트랜잭션이 끝날 때 풀린다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<StaffAccountLock> findForUpdateByStaffIdAndStatus(Long staffId, StaffAccountLockStatus status);

    boolean existsByStaffIdAndStatus(Long staffId, StaffAccountLockStatus status);

    List<StaffAccountLock> findByStaffIdOrderByLockedAtDescLockIdDesc(Long staffId);
}
