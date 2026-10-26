package com.donworry.investigation.service;

import com.donworry.investigation.entity.ActionType;
import java.util.UUID;

// P3 → P5 금융 조치 요청 경계 (fds 정본 02 §2.9, 03 §3.8, ADR-0027)
// 실제 구현은 PaymentFinancialActionGateway(P5 PaymentActionRequester). 시연 결제용 목은 속성으로만 켠다
public interface FinancialActionGateway {

    // 판정 커밋 뒤 · 다시 확인 때 부른다. 같은 멱등키로 다시 불러도 P5는 한 번만 접수하고 저장된 결과를 돌려준다
    Receipt request(Command command);

    record Command(Long paymentId, Long decisionId, ActionType actionType, UUID idempotencyKey) {}

    // 완료 기반 (ADR-0027) — accepted: 실제 매입 · 취소 완료, rejected: 확정 거절, pending: 아직 처리 중(REQUESTED 유지)
    // resultEventFollows: 같은 결과가 P5 결과 이벤트로도 온다. 판정 직후 응답은 반영하지 않고 이벤트로만 반영한다
    record Receipt(Status status, Long targetOperationId, String errorCode, boolean resultEventFollows) {

        public enum Status {
            PENDING,
            ACCEPTED,
            REJECTED
        }

        public static Receipt pending() {
            return new Receipt(Status.PENDING, null, null, false);
        }

        public static Receipt accepted(Long targetOperationId) {
            return new Receipt(Status.ACCEPTED, targetOperationId, null, false);
        }

        public static Receipt rejected(String errorCode) {
            return new Receipt(Status.REJECTED, null, errorCode, false);
        }

        // P5 응답 · 결과 이벤트의 상태 문자열(PENDING · ACCEPTED · REJECTED)을 바꾼다
        public static Receipt of(String status, Long targetOperationId, String errorCode) {
            return new Receipt(Status.valueOf(status), targetOperationId, errorCode, false);
        }

        public Receipt withResultEvent() {
            return new Receipt(status, targetOperationId, errorCode, true);
        }

        public boolean isCompleted() {
            return status != Status.PENDING;
        }
    }
}
