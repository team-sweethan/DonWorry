package com.donworry.investigation.service;

import com.donworry.investigation.entity.ActionType;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;

// 테스트 전용 — TestPaymentInfoStore 결제의 금융 조치를 P5 없이 처리한다
// 예전 앱 안의 시연 목(MockFinancialActionGateway)을 테스트로 옮겼다 (#163). 실제 P5 연결은 PaymentActionFlowTests가 본다
// 실제 P5와 같은 순서로 답한다: 판정 직후 첫 요청은 처리 중(PENDING), 같은 멱등키로 다시 오면(인계 다시 확인) 완료
// TestPaymentInfoStore에 없는 결제는 실제 P5 경로(PaymentFinancialActionGateway)로 보낸다 — 없는 결제의 PAYMENT_NOT_FOUND 같은 실제 응답을 그대로 본다
// 함께 쓰는 TestPaymentInfoStore와 같이 @Import 한다
@Primary
@RequiredArgsConstructor
public class TestFinancialActionGateway implements FinancialActionGateway {

    private final TestPaymentInfoStore payments;
    private final PaymentFinancialActionGateway realGateway;

    private final AtomicLong operationIds = new AtomicLong();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Receipt> completed = new ConcurrentHashMap<>();

    // 완료 뒤 같은 멱등키로 다시 오면 처음 완료 결과를 그대로 돌려준다. 결과 이벤트가 없으므로 응답으로 바로 반영된다
    @Override
    public Receipt request(Command command) {
        if (!payments.contains(command.paymentId())) {
            return realGateway.request(command);
        }
        Receipt done = completed.get(command.idempotencyKey());
        if (done != null) {
            return done;
        }
        if (pending.add(command.idempotencyKey())) {
            return Receipt.pending();
        }
        return completed.computeIfAbsent(command.idempotencyKey(), key -> {
            payments.markCompleted(
                    command.paymentId(), command.actionType() == ActionType.CAPTURE ? "CAPTURED" : "VOIDED");
            return Receipt.accepted(operationIds.incrementAndGet());
        });
    }
}
