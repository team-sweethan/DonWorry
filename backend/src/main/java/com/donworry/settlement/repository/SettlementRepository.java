package com.donworry.settlement.repository;

import com.donworry.settlement.entity.Settlement;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findByPaymentId(Long paymentId);

    Optional<Settlement> findByCaptureId(Long captureId);

    boolean existsByPaymentId(Long paymentId);
}
