package com.donworry.investigation.service.mock;

import com.donworry.investigation.service.FinancialActionGateway.Command;
import com.donworry.investigation.service.FinancialActionGateway.Receipt;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 목업 — 시연 결제(MockPaymentInfoStore)는 P5 payments에 없어서 실제 P5로 보내면 PAYMENT_NOT_FOUND가 된다
// PaymentFinancialActionGateway가 시연 결제의 요청만 여기로 보내고, 바로 완료(ACCEPTED)를 돌려준다
// 시연 API와 함께 지운다 (#163 2단계, Notion "목업 데이터 · 연동 현황" P3 표)
@Slf4j
@Component
public class MockFinancialActionGateway {

    private final AtomicLong operationIds = new AtomicLong();
    private final Map<UUID, Receipt> receipts = new ConcurrentHashMap<>();

    // 같은 멱등키로 다시 오면 처음 결과를 그대로 돌려준다. 결과 이벤트가 없으므로 응답으로 바로 반영된다
    public Receipt request(Command command) {
        return receipts.computeIfAbsent(command.idempotencyKey(), key -> {
            log.info(
                    "목업 금융 조치 완료 paymentId={} decisionId={} actionType={}",
                    command.paymentId(),
                    command.decisionId(),
                    command.actionType());
            return Receipt.accepted(operationIds.incrementAndGet());
        });
    }
}
