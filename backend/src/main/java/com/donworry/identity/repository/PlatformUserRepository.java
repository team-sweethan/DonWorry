package com.donworry.identity.repository;

import com.donworry.identity.entity.PlatformUser;
import com.donworry.identity.entity.UserRole;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlatformUserRepository
        extends JpaRepository<PlatformUser, Long>, JpaSpecificationExecutor<PlatformUser> {

    Optional<PlatformUser> findByLoginId(String loginId);

    List<PlatformUser> findByMerchantIdAndRole(Long merchantId, UserRole role);

    // 두 식 모두 변경 전 횟수를 사용한다. MariaDB의 SET 평가에서도 시각을 먼저 정한다.
    // 잠금 중에는 WHERE에서 제외하고, 만료된 5회는 새로운 1회로 시작한다.
    @Modifying
    @Query("""
            UPDATE PlatformUser u
            SET u.loginLockedUntil = CASE WHEN u.loginFailureCount = 4 THEN :lockedUntil ELSE NULL END,
                u.loginFailureCount = CASE WHEN u.loginFailureCount = 5 THEN 1 ELSE u.loginFailureCount + 1 END
            WHERE u.loginId = :loginId AND u.status = com.donworry.identity.entity.UserStatus.ACTIVE
              AND (u.loginLockedUntil IS NULL OR u.loginLockedUntil <= :now)
            """)
    int recordLoginFailure(
            @Param("loginId") String loginId,
            @Param("now") LocalDateTime now,
            @Param("lockedUntil") LocalDateTime lockedUntil);

    @Modifying
    @Query("UPDATE PlatformUser u SET u.loginFailureCount = 0, u.loginLockedUntil = NULL WHERE u.userId = :userId")
    int clearLoginFailures(@Param("userId") Long userId);
}
