package com.donworry.investigation.service.mock;

import com.donworry.investigation.service.PaymentInfoReader.PaymentInfo;
import com.donworry.investigation.service.PaymentInfoReader.RuleHit;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

// 목업 — 시연 결제(DemoHeldPaymentService)의 P5 결제 정보와 P2 FDS 평가 근거를 메모리에 둔다 (Notion "목업 데이터 · 연동 현황" P3 표)
// 시연 결제는 P5 payments에 없다. 조회 · 금융 조치는 이 저장소에 있는 결제 ID만 목으로 보내고 나머지는 실제 P5 · P2로 보낸다 (#163)
// 앱을 다시 띄우면 비워진다. P5 결제 입구가 켜지면 시연 API와 함께 지운다 (#163 2단계)
@Component
public class MockPaymentInfoStore {

    // 시연 룰의 이름 · 점수 — 시연용 값이다. 프론트 시연 데이터(caseMock.ts FDS_RULES)와 같은 값을 쓴다
    // 기본 조합(첫 사용 카드 + 고액)이 65점, 병합 시연(고액 + 반복)이 60점이 된다
    private static final Map<String, RuleHit> DEMO_RULES = Map.of(
            "NEW_CARD", new RuleHit("NEW_CARD", "첫 사용 카드", 30),
            "HIGH_AMOUNT", new RuleHit("HIGH_AMOUNT", "고액 (50만 원 이상)", 35),
            "RAPID_REPEAT", new RuleHit("RAPID_REPEAT", "10분 내 3회 이상", 25));

    // 실제 payments.payment_id와 겹치지 않도록 큰 값에서 시작한다 (case_payments는 논리 참조라 FK 없음)
    private final AtomicLong paymentIds = new AtomicLong(9_000_000_000L + System.currentTimeMillis() % 1_000_000_000L);
    private final Map<Long, PaymentInfo> payments = new ConcurrentHashMap<>();

    // 시연용 HELD 결제를 만든다 — REVIEW 판정 · 카드 승인 성공 상태로 둔다. 모르는 룰 코드는 이름을 코드로, 점수는 비워 둔다
    public PaymentInfo registerHeld(Long amount, int riskScore, List<String> ruleCodes, Instant requestedAt) {
        List<RuleHit> ruleHits = ruleCodes.stream()
                .map(code -> DEMO_RULES.getOrDefault(code, new RuleHit(code, code, null)))
                .toList();
        PaymentInfo info = new PaymentInfo(
                paymentIds.incrementAndGet(), amount, "HELD", requestedAt, riskScore, "REVIEW", ruleHits);
        payments.put(info.paymentId(), info);
        return info;
    }

    public Optional<PaymentInfo> find(Long paymentId) {
        return Optional.ofNullable(payments.get(paymentId));
    }

    // 시연 결제인지 — 조회 · 금융 조치를 목으로 보낼지 정한다
    public boolean contains(Long paymentId) {
        return payments.containsKey(paymentId);
    }
}
