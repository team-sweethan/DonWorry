package com.donworry.identity.repository;

import com.donworry.identity.entity.UserPermission;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserPermissionRepository extends JpaRepository<UserPermission, Long> {

    @Query("SELECT p.permissionCode FROM UserPermission p WHERE p.userId = :userId ORDER BY p.permissionCode")
    List<String> findPermissionCodesByUserId(Long userId);

    List<UserPermission> findByUserIdInOrderByPermissionCode(Collection<Long> userIds);

    void deleteByUserId(Long userId);
}
