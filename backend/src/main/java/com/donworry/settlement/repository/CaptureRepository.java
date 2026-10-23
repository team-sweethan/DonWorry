package com.donworry.settlement.repository;

import com.donworry.settlement.entity.Capture;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaptureRepository extends JpaRepository<Capture, Long> {

    Optional<Capture> findByPaymentId(Long paymentId);

    boolean existsByPaymentId(Long paymentId);
}
