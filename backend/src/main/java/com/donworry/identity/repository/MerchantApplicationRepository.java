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

    @Modifying
    @Query("delete from MerchantApplication a where a.applicationId=:id")
    int deleteRetained(Long id);

    @Query("select coalesce(max(a.applicationId),0) from MerchantApplication a")
    long maximumId();

    @Query(
            "select a.applicationId from MerchantApplication a where a.applicationId>:cursor and a.applicationId<=:upper and ((a.status=com.donworry.identity.entity.ApplicationStatus.EMAIL_PENDING and a.createdAt<=:emailCutoff) or (a.status=com.donworry.identity.entity.ApplicationStatus.REJECTED and a.reviewedAt<=:rejectionCutoff)) order by a.applicationId")
    java.util.List<Long> retentionCandidates(
            long cursor,
            long upper,
            java.time.LocalDateTime emailCutoff,
            java.time.LocalDateTime rejectionCutoff,
            org.springframework.data.domain.Pageable page);

    // 잠그지 않고 id만 찾는다. 잠금은 lockById로 기본키에 먼저 걸어 이메일 확인과 잠금 순서를 맞춘다 (#335)
    @Query(
            "select a.applicationId from MerchantApplication a where a.email = :email and a.status = com.donworry.identity.entity.ApplicationStatus.EMAIL_PENDING")
    Optional<Long> findUnverifiedIdByEmail(String email);
}
