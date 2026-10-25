package com.donworry.identity.repository;

import com.donworry.identity.entity.Merchant;
import com.donworry.identity.entity.MerchantStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

public interface MerchantRepository extends Repository<Merchant, Long> {
    Optional<Merchant> findById(Long merchantId);

    List<Merchant> findByMerchantIdIn(Collection<Long> merchantIds);

    List<Merchant> findByStatusAndDisplayNameContainingIgnoreCaseOrderByMerchantId(
            MerchantStatus status, String keyword, Pageable pageable);
}
