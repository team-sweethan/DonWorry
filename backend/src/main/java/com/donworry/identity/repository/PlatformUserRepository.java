package com.donworry.identity.repository;

import com.donworry.identity.entity.PlatformUser;
import com.donworry.identity.entity.UserRole;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlatformUserRepository
        extends JpaRepository<PlatformUser, Long>, JpaSpecificationExecutor<PlatformUser> {

    Optional<PlatformUser> findByLoginId(String loginId);

    // 계정 행 잠금 읽기(SELECT ... FOR UPDATE) — 보안 정지 해제가 부른 쪽(판정) 트랜잭션 끝까지 계정 행을 잡는다.
    // 그동안 비밀번호 초기화 · 상태 변경의 UPDATE는 기다리고, 해제는 그 사이 바뀐 비밀번호를 옛 값으로 되돌리지 않는다 (#649 리뷰)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PlatformUser> findForUpdateByUserId(Long userId);

    List<PlatformUser> findByMerchantIdAndRole(Long merchantId, UserRole role);

    boolean existsByMerchantIdAndRole(Long merchantId, UserRole role);

    // 보안 직원 목록 (SecurityStaffDirectory) — 직원 ID 오름차순
    List<PlatformUser> findByRoleOrderByUserIdAsc(UserRole role);

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

    // 실패 기록이 없는 계정은 갱신하지 않아 성공 로그인마다 쓰기가 생기지 않게 한다.
    @Modifying
    @Query("""
            UPDATE PlatformUser u SET u.loginFailureCount = 0, u.loginLockedUntil = NULL
            WHERE u.userId = :userId AND (u.loginFailureCount <> 0 OR u.loginLockedUntil IS NOT NULL)
            """)
    int clearLoginFailures(@Param("userId") Long userId);
}
