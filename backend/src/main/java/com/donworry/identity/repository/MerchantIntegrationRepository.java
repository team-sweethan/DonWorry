package com.donworry.identity.repository;

import com.donworry.identity.entity.MerchantIntegration;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface MerchantIntegrationRepository extends Repository<MerchantIntegration, Long> {
    Optional<MerchantIntegration> findByMerchantId(Long merchantId);

    MerchantIntegration saveAndFlush(MerchantIntegration integration);
}
