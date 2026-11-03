package com.donworry.identity.repository;

import com.donworry.identity.entity.*;
import com.donworry.identity.entity.ApplicationMailDelivery;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface ApplicationMailDeliveryRepository extends JpaRepository<ApplicationMailDelivery, Long> {
    Optional<ApplicationMailDelivery> findFirstByApplicationIdAndKindOrderByDeliveryIdDesc(
            Long id, ApplicationMailKind kind);

    List<ApplicationMailDelivery> findByApplicationIdAndKindOrderByDeliveryIdDesc(Long id, ApplicationMailKind kind);

    Page<ApplicationMailDelivery> findByApplicationId(Long id, Pageable pageable);

    @Query(
            "select d from ApplicationMailDelivery d where d.applicationId in :ids and d.deliveryId = (select max(x.deliveryId) from ApplicationMailDelivery x where x.applicationId=d.applicationId and x.kind=d.kind)")
    List<ApplicationMailDelivery> findLatestForApplications(Collection<Long> ids);

    @Modifying
    @Query(
            "update ApplicationMailDelivery d set d.status=:status, d.failureCode=:failure, d.completedAt=:now where d.deliveryId=:id and d.status=com.donworry.identity.entity.ApplicationMailStatus.PENDING")
    int completePending(Long id, ApplicationMailStatus status, String failure, LocalDateTime now);

    @Modifying
    @Query("delete from ApplicationMailDelivery d where d.applicationId=:id")
    int deleteForApplication(Long id);
}
