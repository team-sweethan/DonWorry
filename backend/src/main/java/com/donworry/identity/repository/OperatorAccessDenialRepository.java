package com.donworry.identity.repository;

import com.donworry.identity.entity.OperatorAccessDenial;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

// 조사 원본 행은 삭제하지 않고, 보관 기간이 지난 IP만 비운다.
public interface OperatorAccessDenialRepository extends Repository<OperatorAccessDenial, Long> {
    OperatorAccessDenial saveAndFlush(OperatorAccessDenial denial);

    @Modifying
    @Query(
            value =
                    "UPDATE operator_access_denials SET client_ip = NULL WHERE occurred_at <= CAST(:cutoff AS DATETIME(6)) AND client_ip IS NOT NULL ORDER BY occurred_at, denial_id LIMIT 1000",
            nativeQuery = true)
    int clearExpiredIpsBatch(String cutoff);
}
