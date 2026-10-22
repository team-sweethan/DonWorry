package com.donworry.identity.repository;

import com.donworry.identity.entity.PlatformUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformUserRepository extends JpaRepository<PlatformUser, Long> {

    Optional<PlatformUser> findByLoginId(String loginId);
}
