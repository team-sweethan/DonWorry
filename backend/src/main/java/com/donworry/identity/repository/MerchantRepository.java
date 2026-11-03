package com.donworry.identity.repository;

import com.donworry.identity.entity.Merchant;
import com.donworry.identity.entity.MerchantStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

// 결제·정산 기록이 가맹점 ID를 가리키므로 ID 기반 삭제 메서드를 열지 않고 필요한 메서드만 선언한다.
// JpaSpecificationExecutor.delete(Specification)은 남으므로 가맹점 삭제 코드를 만들지 않는다.
public interface MerchantRepository extends Repository<Merchant, Long>, JpaSpecificationExecutor<Merchant> {
    Optional<Merchant> findById(Long merchantId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT m FROM Merchant m WHERE m.merchantId = :merchantId")
    Optional<Merchant> lockById(@org.springframework.data.repository.query.Param("merchantId") Long merchantId);

    Optional<Merchant> findByMerchantCode(String merchantCode);

    Merchant saveAndFlush(Merchant merchant);

    List<Merchant> findByBusinessRegistrationNumber(String businessRegistrationNumber);

    boolean existsByMerchantCode(String merchantCode);

    List<Merchant> findByMerchantIdIn(Collection<Long> merchantIds);

    List<Merchant> findAllByOrderByMerchantIdAsc();

    List<Merchant> findByStatusAndDisplayNameContainingIgnoreCaseOrderByMerchantId(
            MerchantStatus status, String keyword, Pageable pageable);
}
