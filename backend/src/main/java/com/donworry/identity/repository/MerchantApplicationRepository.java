package com.donworry.identity.repository;

import com.donworry.identity.entity.MerchantApplication;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface MerchantApplicationRepository extends JpaRepository<MerchantApplication, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from MerchantApplication a where a.applicationId = :id")
    Optional<MerchantApplication> lockById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select a from MerchantApplication a where a.email = :email and a.status = com.donworry.identity.entity.ApplicationStatus.EMAIL_PENDING")
    Optional<MerchantApplication> lockUnverifiedByEmail(String email);
}
