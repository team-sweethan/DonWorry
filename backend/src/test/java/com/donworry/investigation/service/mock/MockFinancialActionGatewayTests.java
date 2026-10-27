package com.donworry.investigation.service.mock;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.investigation.entity.ActionType;
import com.donworry.investigation.service.FinancialActionGateway.Command;
import com.donworry.investigation.service.FinancialActionGateway.Receipt;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// 시연 결제의 금융 조치 목업 — 실제 P5처럼 처음엔 처리 중, 다시 확인하면 완료되고 결제 상태가 바뀐다 (#212)
class MockFinancialActionGatewayTests {

    MockPaymentInfoStore store = new MockPaymentInfoStore();
    MockFinancialActionGateway gateway = new MockFinancialActionGateway(store);

    @Test
    void request_firstPendingThenCompletedOnRecheck_capturesPayment() {
        Command command = command(ActionType.CAPTURE);

        Receipt first = gateway.request(command);
        Receipt recheck = gateway.request(command);

        assertThat(first.status()).isEqualTo(Receipt.Status.PENDING);
        assertThat(recheck.status()).isEqualTo(Receipt.Status.ACCEPTED);
        assertThat(recheck.targetOperationId()).isNotNull();
        assertThat(store.find(command.paymentId()).orElseThrow().paymentStatus())
                .isEqualTo("CAPTURED");
    }

    @Test
    void request_void_marksPaymentVoided() {
        Command command = command(ActionType.VOID);

        gateway.request(command);
        gateway.request(command);

        assertThat(store.find(command.paymentId()).orElseThrow().paymentStatus())
                .isEqualTo("VOIDED");
    }

    // 처리 중일 때는 결제 상태를 바꾸지 않는다
    @Test
    void request_whilePending_keepsPaymentHeld() {
        Command command = command(ActionType.CAPTURE);

        gateway.request(command);

        assertThat(store.find(command.paymentId()).orElseThrow().paymentStatus())
                .isEqualTo("HELD");
    }

    // 완료 뒤 같은 멱등키로 다시 와도 같은 결과를 돌려준다
    @Test
    void request_afterCompletion_returnsSameReceipt() {
        Command command = command(ActionType.CAPTURE);
        gateway.request(command);

        Receipt completed = gateway.request(command);
        Receipt again = gateway.request(command);

        assertThat(again).isEqualTo(completed);
    }

    private Command command(ActionType actionType) {
        Long paymentId = store.registerHeld(100_000L, 65, List.of("NEW_CARD"), Instant.now())
                .paymentId();
        return new Command(paymentId, 1L, actionType, UUID.randomUUID());
    }
}
