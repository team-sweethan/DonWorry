package com.donworry.identity.repository;

import com.donworry.identity.entity.Merchant;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface MerchantRepository extends Repository<Merchant, Long> {
    Optional<Merchant> findById(Long merchantId);
}
