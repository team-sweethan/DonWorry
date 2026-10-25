package com.donworry.identity.repository;

import com.donworry.identity.entity.Merchant;
import com.donworry.identity.entity.MerchantStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

// 결제·정산 기록이 가맹점 ID를 가리키므로 삭제 메서드를 열지 않는다. 필요한 메서드만 선언한다.
public interface MerchantRepository extends Repository<Merchant, Long>, JpaSpecificationExecutor<Merchant> {
    Optional<Merchant> findById(Long merchantId);

    Merchant saveAndFlush(Merchant merchant);

    boolean existsByMerchantCode(String merchantCode);

    List<Merchant> findByMerchantIdIn(Collection<Long> merchantIds);

    List<Merchant> findByStatusAndDisplayNameContainingIgnoreCaseOrderByMerchantId(
            MerchantStatus status, String keyword, Pageable pageable);
}
