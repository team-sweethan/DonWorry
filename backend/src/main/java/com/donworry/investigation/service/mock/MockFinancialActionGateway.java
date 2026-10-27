package com.donworry.investigation.service.mock;

import com.donworry.investigation.entity.ActionType;
import com.donworry.investigation.service.FinancialActionGateway.Command;
import com.donworry.investigation.service.FinancialActionGateway.Receipt;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 목업 — 시연 결제(MockPaymentInfoStore)는 P5 payments에 없어서 실제 P5로 보내면 PAYMENT_NOT_FOUND가 된다
// PaymentFinancialActionGateway가 시연 결제의 요청만 여기로 보낸다
// 실제 P5와 같은 순서로 보이게 한다 (#212): 판정 직후 첫 요청은 처리 중(PENDING), 같은 멱등키로 다시 오면(인계 상태 다시 확인) 완료
// 완료할 때 목 결제 상태도 CAPTURED · VOIDED로 바꾼다. 시간이 지났다고 스스로 완료하지 않는다 (fds 정본 02 §2.9)
// 시연 API와 함께 지운다 (#163 2단계, Notion "목업 데이터 · 연동 현황" P3 표)
@Slf4j
@Component
@RequiredArgsConstructor
public class MockFinancialActionGateway {

    private final MockPaymentInfoStore mockPaymentInfoStore;

    private final AtomicLong operationIds = new AtomicLong();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Receipt> completed = new ConcurrentHashMap<>();

    // 완료 뒤 같은 멱등키로 다시 오면 처음 완료 결과를 그대로 돌려준다. 결과 이벤트가 없으므로 응답으로 바로 반영된다
    public Receipt request(Command command) {
        Receipt done = completed.get(command.idempotencyKey());
        if (done != null) {
            return done;
        }
        if (pending.add(command.idempotencyKey())) {
            log.info(
                    "목업 금융 조치 접수(처리 중) paymentId={} decisionId={} actionType={}",
                    command.paymentId(),
                    command.decisionId(),
                    command.actionType());
            return Receipt.pending();
        }
        return completed.computeIfAbsent(command.idempotencyKey(), key -> {
            mockPaymentInfoStore.markCompleted(
                    command.paymentId(), command.actionType() == ActionType.CAPTURE ? "CAPTURED" : "VOIDED");
            log.info(
                    "목업 금융 조치 완료 paymentId={} decisionId={} actionType={}",
                    command.paymentId(),
                    command.decisionId(),
                    command.actionType());
            return Receipt.accepted(operationIds.incrementAndGet());
        });
    }
}
