package com.donworry.identity.repository;

import com.donworry.identity.entity.PlatformUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PlatformUserRepository
        extends JpaRepository<PlatformUser, Long>, JpaSpecificationExecutor<PlatformUser> {

    Optional<PlatformUser> findByLoginId(String loginId);
}
