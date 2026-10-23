package com.donworry.investigation.service;

import com.donworry.investigation.entity.ActionType;
import java.util.UUID;

// P3 → P5 금융 조치 요청 경계 (fds 정본 02 §2.9, 03 §3.8)
// 지금은 목 구현(MockFinancialActionGateway)을 쓴다. P5 공개 API가 생기면 구현만 바꾼다
public interface FinancialActionGateway {

    // 판정 커밋 뒤에만 부른다. 같은 멱등키로 다시 불러도 P5는 한 번만 접수해야 한다
    Receipt request(Command command);

    record Command(Long paymentId, Long decisionId, ActionType actionType, UUID idempotencyKey) {}

    // accepted: 담당 모듈 영속 접수 확인, rejected: 접수 거절 확인. 둘 다 아니면 아직 모름(REQUESTED 유지)
    record Receipt(Status status, Long targetOperationId, String errorCode) {

        public enum Status {
            ACCEPTED,
            REJECTED,
            UNKNOWN
        }

        public static Receipt accepted(Long targetOperationId) {
            return new Receipt(Status.ACCEPTED, targetOperationId, null);
        }

        public static Receipt rejected(String errorCode) {
            return new Receipt(Status.REJECTED, null, errorCode);
        }

        public static Receipt unknown() {
            return new Receipt(Status.UNKNOWN, null, null);
        }
    }
}
