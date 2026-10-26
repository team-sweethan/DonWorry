package com.donworry.investigation.service;

import com.donworry.payment.api.PaymentActionCommand;
import com.donworry.payment.api.PaymentActionReceipt;
import com.donworry.payment.api.PaymentActionRequester;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// P5 공개 계약으로 금융 조치를 요청한다 (ADR-0027, #158 · #169)
// 시연 결제용 목(investigation.mock.payment-action-enabled=true)을 켜면 이 구현 대신 목이 뜬다
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "investigation.mock",
        name = "payment-action-enabled",
        havingValue = "false",
        matchIfMissing = true)
@RequiredArgsConstructor
class PaymentFinancialActionGateway implements FinancialActionGateway {

    // 접수 단계 거절 — 응답으로만 오고 결과 이벤트가 없다 (#169). 그 밖의 완료 결과는 이벤트로도 온다
    private static final Set<String> RESPONSE_ONLY_CODES = Set.of(
            "PAYMENT_NOT_FOUND",
            "PAYMENT_NOT_HELD",
            "PAYMENT_NOT_AUTHORIZED",
            "IDEMPOTENCY_KEY_CONFLICT",
            "PAYMENT_ACTION_CONFLICT",
            "DECISION_ACTION_CONFLICT");

    // 정상 흐름에서는 생기지 않는다 — P3가 같은 결제 · 판정 · 키를 잘못 보냈다는 신호다
    private static final Set<String> P3_BUG_CODES =
            Set.of("IDEMPOTENCY_KEY_CONFLICT", "PAYMENT_ACTION_CONFLICT", "DECISION_ACTION_CONFLICT");

    private final PaymentActionRequester paymentActionRequester;

    @Override
    public Receipt request(Command command) {
        PaymentActionReceipt response = paymentActionRequester.request(new PaymentActionCommand(
                command.paymentId(), command.decisionId(), command.actionType().name(), command.idempotencyKey()));
        Receipt receipt = Receipt.of(response.status(), response.targetOperationId(), response.errorCode());
        if (!receipt.isCompleted()) {
            return receipt;
        }
        if (receipt.status() == Receipt.Status.REJECTED && RESPONSE_ONLY_CODES.contains(receipt.errorCode())) {
            if (P3_BUG_CODES.contains(receipt.errorCode())) {
                log.error(
                        "금융 조치 요청 충돌 errorCode={} paymentId={} decisionId={} idempotencyKey={}",
                        receipt.errorCode(),
                        command.paymentId(),
                        command.decisionId(),
                        command.idempotencyKey());
            }
            return receipt;
        }
        return receipt.withResultEvent();
    }
}
