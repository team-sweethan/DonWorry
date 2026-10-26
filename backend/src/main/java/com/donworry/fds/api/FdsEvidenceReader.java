package com.donworry.fds.api;

import java.util.Collection;
import java.util.List;

// P3는 Case 접근 권한을 확인한 결제 ID만 전달해 평가 근거를 조회한다.
public interface FdsEvidenceReader {

    List<FdsPaymentEvidence> findLatestByPaymentIds(Collection<Long> paymentIds);
}
