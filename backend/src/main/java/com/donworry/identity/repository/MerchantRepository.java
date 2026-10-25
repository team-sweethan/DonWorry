package com.donworry.identity.repository;

import com.donworry.identity.entity.Merchant;
import com.donworry.identity.entity.MerchantStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MerchantRepository extends JpaRepository<Merchant, Long>, JpaSpecificationExecutor<Merchant> {
    boolean existsByMerchantCode(String merchantCode);

    List<Merchant> findByMerchantIdIn(Collection<Long> merchantIds);

    List<Merchant> findByStatusAndDisplayNameContainingIgnoreCaseOrderByMerchantId(
            MerchantStatus status, String keyword, Pageable pageable);
}
