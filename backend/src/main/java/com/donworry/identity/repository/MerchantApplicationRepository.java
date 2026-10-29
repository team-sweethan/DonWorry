package com.donworry.identity.repository;

import com.donworry.identity.entity.MerchantApplication;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface MerchantApplicationRepository
        extends JpaRepository<MerchantApplication, Long>, JpaSpecificationExecutor<MerchantApplication> {
    @Query(
            "select a from MerchantApplication a where a.requestedLoginId = :loginId and a.status in (com.donworry.identity.entity.ApplicationStatus.EMAIL_PENDING, com.donworry.identity.entity.ApplicationStatus.REVIEW_PENDING)")
    Optional<MerchantApplication> findReservedLoginId(String loginId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from MerchantApplication a where a.applicationId = :id")
    Optional<MerchantApplication> lockById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select a from MerchantApplication a where a.email = :email and a.status = com.donworry.identity.entity.ApplicationStatus.EMAIL_PENDING")
    Optional<MerchantApplication> lockUnverifiedByEmail(String email);
}
