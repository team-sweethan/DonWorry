package com.donworry.identity.repository;

import com.donworry.identity.entity.StaffAccountLock;
import com.donworry.identity.entity.StaffAccountLockStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

// 정지 기록은 지우지 않는다(조사 기록). 삭제 메서드를 열지 않는다
public interface StaffAccountLockRepository extends Repository<StaffAccountLock, Long> {
    StaffAccountLock saveAndFlush(StaffAccountLock lock);

    Optional<StaffAccountLock> findByStaffIdAndStatus(Long staffId, StaffAccountLockStatus status);

    boolean existsByStaffIdAndStatus(Long staffId, StaffAccountLockStatus status);

    List<StaffAccountLock> findByStaffIdOrderByLockedAtDescLockIdDesc(Long staffId);
}
