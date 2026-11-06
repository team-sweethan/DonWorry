package com.donworry.investigation.repository;

import com.donworry.investigation.entity.RawViewReview;
import com.donworry.investigation.entity.RawViewReviewStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 원문 열람 사후 검토 (#619) — 조사 기록이라 삭제를 노출하지 않는다
public interface RawViewReviewRepository extends Repository<RawViewReview, Long> {
    RawViewReview save(RawViewReview review);

    // 두 ADMIN이 동시에 처리해도 한 번만 바뀌게 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RawViewReview r WHERE r.id = :id")
    Optional<RawViewReview> findByIdForUpdate(@Param("id") Long id);

    List<RawViewReview> findTop100ByStatusOrderByViewedAtDescIdDesc(RawViewReviewStatus status);

    List<RawViewReview> findByCaseId(Long caseId);

    long countByStatus(RawViewReviewStatus status);
}
