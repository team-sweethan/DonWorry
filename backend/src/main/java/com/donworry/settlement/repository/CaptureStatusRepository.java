package com.donworry.settlement.repository;

import com.donworry.settlement.entity.Capture;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface CaptureStatusRepository extends Repository<Capture, Long> {
    // 매입과 정산을 따로 읽을 때 생기는 관측 차이를 줄이고 정산 누락도 그대로 반환한다.
    @Query("""
            SELECT c.paymentId AS paymentId, c.captureId AS captureId,
                   c.status AS captureStatus, c.captureAmount AS captureAmount,
                   c.capturedAt AS capturedAt, s.settlementId AS settlementId,
                   s.status AS settlementStatus
            FROM Capture c LEFT JOIN Settlement s ON s.captureId = c.captureId
            WHERE c.paymentId = :paymentId
            """)
    Optional<CaptureStatusProjection> findStatusByPaymentId(@Param("paymentId") Long paymentId);
}
