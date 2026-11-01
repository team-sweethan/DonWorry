package com.donworry.identity.repository;

import com.donworry.identity.entity.MerchantKeyHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

public interface MerchantKeyHistoryRepository extends Repository<MerchantKeyHistory, Long> {
    MerchantKeyHistory save(MerchantKeyHistory history);

    Page<MerchantKeyHistory> findByMerchantIdOrderByHistoryIdDesc(Long merchantId, Pageable pageable);
}
