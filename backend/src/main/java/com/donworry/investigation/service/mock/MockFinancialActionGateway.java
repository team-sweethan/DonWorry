package com.donworry.investigation.service.mock;

import com.donworry.investigation.service.FinancialActionGateway;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 목업 — P5 금융 조치 접수를 흉내 내 바로 ACCEPTED를 돌려준다 (Notion "목업 데이터 · 연동 현황" P3 표)
// 실제로는 ACCEPT(CAPTURE)는 P6 영속 접수가 P5를 거쳐 이벤트로 오고, REJECT(VOID)는 P5 동기 응답으로 온다
// P5 공개 API가 생기면 이 클래스를 지운다
@Slf4j
@Component
public class MockFinancialActionGateway implements FinancialActionGateway {

    private final AtomicLong operationIds = new AtomicLong();
    private final Map<UUID, Receipt> receipts = new ConcurrentHashMap<>();

    // 같은 멱등키로 다시 오면 처음 접수 결과를 그대로 돌려준다
    @Override
    public Receipt request(Command command) {
        return receipts.computeIfAbsent(command.idempotencyKey(), key -> {
            log.info(
                    "목업 금융 조치 접수 paymentId={} decisionId={} actionType={}",
                    command.paymentId(),
                    command.decisionId(),
                    command.actionType());
            return Receipt.accepted(operationIds.incrementAndGet());
        });
    }
}
