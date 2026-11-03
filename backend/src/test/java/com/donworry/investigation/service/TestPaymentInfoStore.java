package com.donworry.investigation.service;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Primary;

// 테스트 전용 — P5 결제 · P2 평가 없이 Case 흐름을 보려고 결제 정보를 메모리에 둔다
// 예전 앱 안의 시연 목(MockPaymentInfoStore)을 테스트로 옮겼다 (#163). 실제 P5 · P2 연결은 PaymentFdsInfoReaderTests가 본다
// 쓰는 테스트에서 @Import 하면 실제 PaymentFdsInfoReader 대신 이 저장소가 쓰인다
@Primary
// 고객 HELD 승인 취소(#382)도 P5 원본 대신 이 저장소에서 읽는다 — markCustomerVoided로 만든다
public class TestPaymentInfoStore implements PaymentInfoReader, CustomerVoidReader {

    // 룰 이름 · 점수는 테스트 값이다. 기본 조합(첫 사용 카드 + 고액)이 65점이다
    public static final List<String> DEFAULT_RULES = List.of("NEW_CARD", "HIGH_AMOUNT");

    private static final Map<String, RuleHit> RULES = Map.of(
            "NEW_CARD", new RuleHit("NEW_CARD", "첫 사용 카드", 30),
            "HIGH_AMOUNT", new RuleHit("HIGH_AMOUNT", "고액 (50만 원 이상)", 35),
            "RAPID_REPEAT", new RuleHit("RAPID_REPEAT", "10분 내 3회 이상", 25));

    // 다른 테스트가 실제로 만든 payments.payment_id와 겹치지 않도록 큰 값에서 시작한다 (case_payments는 논리 참조)
    private final AtomicLong paymentIds = new AtomicLong(9_000_000_000L + System.currentTimeMillis() % 1_000_000_000L);
    private final Map<Long, PaymentInfo> payments = new ConcurrentHashMap<>();
    private final Map<Long, CustomerVoid> customerVoids = new ConcurrentHashMap<>();
    private final AtomicLong cancellationIds = new AtomicLong(paymentIds.get());

    // REVIEW 판정 · 카드 승인 성공(HELD) 결제를 만든다. 모르는 룰 코드는 이름을 코드로, 점수는 비워 둔다
    public PaymentInfo registerHeld(Long amount, int riskScore, List<String> ruleCodes, Instant requestedAt) {
        List<RuleHit> ruleHits = ruleCodes.stream()
                .map(code -> RULES.getOrDefault(code, new RuleHit(code, code, null)))
                .toList();
        PaymentInfo info = new PaymentInfo(
                paymentIds.incrementAndGet(), amount, "HELD", requestedAt, riskScore, "REVIEW", ruleHits);
        payments.put(info.paymentId(), info);
        return info;
    }

    // 금융 조치가 완료되면 P5처럼 결제 상태를 바꾼다 — CAPTURED · VOIDED
    public void markCompleted(Long paymentId, String paymentStatus) {
        payments.computeIfPresent(
                paymentId,
                (id, info) -> new PaymentInfo(
                        info.paymentId(),
                        info.amount(),
                        paymentStatus,
                        info.requestedAt(),
                        info.riskScore(),
                        info.fdsResult(),
                        info.ruleHits()));
    }

    // P5에서 고객이 HELD 결제 승인을 취소한 것처럼 만든다 — 결제는 VOIDED가 되고 고객 취소 성공 원본이 생긴다
    public CustomerVoid markCustomerVoided(Long merchantId, Long paymentId, Instant completedAt) {
        markCompleted(paymentId, "VOIDED");
        CustomerVoid customerVoid = new CustomerVoid(
                cancellationIds.incrementAndGet(),
                paymentId,
                merchantId,
                payments.get(paymentId).amount(),
                completedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        customerVoids.put(paymentId, customerVoid);
        return customerVoid;
    }

    @Override
    public Optional<CustomerVoid> find(Long merchantId, Long paymentId) {
        return Optional.ofNullable(customerVoids.get(paymentId))
                .filter(customerVoid -> customerVoid.merchantId().equals(merchantId));
    }

    public boolean contains(Long paymentId) {
        return payments.containsKey(paymentId);
    }

    @Override
    public Map<Long, PaymentInfo> findAll(Collection<Long> ids) {
        Map<Long, PaymentInfo> found = new LinkedHashMap<>();
        for (Long id : ids) {
            PaymentInfo info = payments.get(id);
            if (info != null) {
                found.put(id, info);
            }
        }
        return found;
    }
}
