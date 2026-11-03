package com.donworry.settlement.api;

import java.util.Optional;

/** 저장된 P6 결과만 조회하며 요청 전달 여부나 최종 실패를 판정하지 않는다. */
public interface CaptureReader {
    Optional<CaptureSnapshot> findByPaymentId(Long paymentId);
}
