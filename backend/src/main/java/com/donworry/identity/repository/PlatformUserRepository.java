package com.donworry.identity.repository;

import com.donworry.identity.entity.PlatformUser;
import com.donworry.identity.entity.UserRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PlatformUserRepository
        extends JpaRepository<PlatformUser, Long>, JpaSpecificationExecutor<PlatformUser> {

    Optional<PlatformUser> findByLoginId(String loginId);

    List<PlatformUser> findByMerchantIdAndRole(Long merchantId, UserRole role);
}
