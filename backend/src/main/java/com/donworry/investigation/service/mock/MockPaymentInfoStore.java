package com.donworry.investigation.service.mock;

import com.donworry.investigation.service.PaymentInfoReader;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

// 목업 — P5 결제 정보와 P2 FDS 평가 근거를 메모리에 둔다 (Notion "목업 데이터 · 연동 현황" P3 표)
// 앱을 다시 띄우면 비워진다. P5 · P2 공개 조회 계약이 생기면 이 클래스를 지운다
@Component
public class MockPaymentInfoStore implements PaymentInfoReader {

    // 실제 payments.payment_id와 겹치지 않도록 큰 값에서 시작한다 (case_payments는 논리 참조라 FK 없음)
    private final AtomicLong paymentIds = new AtomicLong(9_000_000_000L + System.currentTimeMillis() % 1_000_000_000L);
    private final Map<Long, PaymentInfo> payments = new ConcurrentHashMap<>();

    // 데모용 HELD 결제를 만든다 — REVIEW 판정 · 카드 승인 성공 상태로 둔다
    public PaymentInfo registerHeld(Long amount, int riskScore, List<String> ruleHits, Instant requestedAt) {
        PaymentInfo info = new PaymentInfo(
                paymentIds.incrementAndGet(), amount, "HELD", requestedAt, riskScore, "REVIEW", ruleHits);
        payments.put(info.paymentId(), info);
        return info;
    }

    @Override
    public Optional<PaymentInfo> find(Long paymentId) {
        return Optional.ofNullable(payments.get(paymentId));
    }
}
