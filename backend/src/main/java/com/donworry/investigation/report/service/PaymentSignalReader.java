package com.donworry.investigation.report.service;

import com.donworry.fds.api.FdsEvidenceReader;
import com.donworry.fds.api.FdsPaymentEvidence;
import com.donworry.investigation.report.dto.FdsEvidenceToolResponse.Signals;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 결제별 카드 · 기기 · 접속 국가 요약 (#346) — P2 공개 창구를 직접 읽는다 (P3 결제 정보 창구는 거치지 않음)
// P2가 원문 식별자를 빼고 주므로 여기서도 표시값 · 여부만 다룬다. FDS 평가가 없는 결제(시연 결제 등)는 결과에 없다
@Component
@RequiredArgsConstructor
public class PaymentSignalReader {

    private final FdsEvidenceReader fdsEvidenceReader;

    public Map<Long, Signals> findAll(Collection<Long> paymentIds) {
        if (paymentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Signals> found = new HashMap<>();
        for (FdsPaymentEvidence evidence : fdsEvidenceReader.findLatestByPaymentIds(paymentIds)) {
            FdsPaymentEvidence.PaymentSignals s = evidence.signals();
            if (s != null) {
                found.put(
                        evidence.paymentId(),
                        new Signals(
                                s.cardDisplayLabel(),
                                s.cardProvided(),
                                s.deviceProvided(),
                                s.ipCountryCode(),
                                s.ipProvided()));
            }
        }
        return found;
    }
}
