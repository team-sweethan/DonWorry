package com.donworry.identity.repository;

import com.donworry.identity.entity.*;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationTokenRepository extends JpaRepository<ApplicationToken, Long> {
    Optional<ApplicationToken> findByTokenHash(String tokenHash);

    List<ApplicationToken> findByApplicationIdAndPurpose(Long applicationId, ApplicationTokenPurpose purpose);
}
