package com.donworry.payment.service;

import com.donworry.payment.entity.PaymentStatus;
import java.util.Objects;

/** P5 내부의 순수 판단 규칙. 반환된 다음 단계의 저장·호출·발행은 호출자가 담당한다. */
public final class PaymentRules {

    private PaymentRules() {}

    // P2·P3 공개 DTO가 확정되기 전 사용하는 내부 입력 타입이다.
    public enum FdsVerdict {
        ALLOW,
        REVIEW,
        DENY,
        ERROR
    }

    public enum AuthorizationOutcome {
        SUCCEEDED,
        DECLINED,
        TECHNICAL_FAILURE
    }

    public enum AuthorizationStatus {
        NOT_REQUESTED,
        SUCCEEDED,
        DECLINED,
        UNKNOWN
    }

    public enum StaffDecision {
        ACCEPT,
        REJECT
    }

    public enum NextStep {
        NONE,
        REQUEST_AUTHORIZATION,
        RECORD_FDS_ERROR,
        NOTIFY_HELD,
        REQUEST_CAPTURE,
        REQUEST_VOID,
        RECONCILE_AUTHORIZATION
    }

    /** 승인 성공은 최초 승인 사실이다. VOIDED·EXPIRED도 그 승인 사실은 보존한다. */
    public record State(PaymentStatus status, AuthorizationStatus authorizationStatus) {
        public State {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(authorizationStatus, "authorizationStatus");
            boolean consistent = switch (status) {
                case PENDING ->
                    authorizationStatus == AuthorizationStatus.NOT_REQUESTED
                            || authorizationStatus == AuthorizationStatus.SUCCEEDED
                            || authorizationStatus == AuthorizationStatus.UNKNOWN;
                case DENIED -> authorizationStatus == AuthorizationStatus.NOT_REQUESTED;
                case DECLINED -> authorizationStatus == AuthorizationStatus.DECLINED;
                case HELD, CAPTURED, VOIDED, EXPIRED -> authorizationStatus == AuthorizationStatus.SUCCEEDED;
            };
            if (!consistent) {
                throw new IllegalArgumentException("결제 상태와 승인 결과가 일치하지 않습니다");
            }
        }

        public static State pending() {
            return new State(PaymentStatus.PENDING, AuthorizationStatus.NOT_REQUESTED);
        }
    }

    public record Decision(State state, NextStep nextStep) {
        public Decision {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(nextStep, "nextStep");
        }
    }

    public static Decision afterFds(State state, FdsVerdict verdict) {
        requireUnapprovedPending(state);
        Objects.requireNonNull(verdict, "verdict");
        return switch (verdict) {
            case ALLOW, REVIEW -> new Decision(state, NextStep.REQUEST_AUTHORIZATION);
            case DENY ->
                new Decision(new State(PaymentStatus.DENIED, AuthorizationStatus.NOT_REQUESTED), NextStep.NONE);
            case ERROR -> new Decision(state, NextStep.RECORD_FDS_ERROR);
        };
    }

    /** 호출자는 이 승인 요청에 채택한 FDS 판정을 전달해야 한다. */
    public static Decision afterAuthorization(State state, FdsVerdict verdict, AuthorizationOutcome outcome) {
        requireUnapprovedPending(state);
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(outcome, "outcome");
        if (verdict != FdsVerdict.ALLOW && verdict != FdsVerdict.REVIEW) {
            throw new IllegalArgumentException("ALLOW 또는 REVIEW일 때만 승인 결과를 처리할 수 있습니다");
        }
        return switch (outcome) {
            case SUCCEEDED ->
                verdict == FdsVerdict.REVIEW
                        ? new Decision(
                                new State(PaymentStatus.HELD, AuthorizationStatus.SUCCEEDED), NextStep.NOTIFY_HELD)
                        : new Decision(
                                new State(PaymentStatus.PENDING, AuthorizationStatus.SUCCEEDED),
                                NextStep.REQUEST_CAPTURE);
            case DECLINED ->
                new Decision(new State(PaymentStatus.DECLINED, AuthorizationStatus.DECLINED), NextStep.NONE);
            case TECHNICAL_FAILURE ->
                new Decision(
                        new State(PaymentStatus.PENDING, AuthorizationStatus.UNKNOWN),
                        NextStep.RECONCILE_AUTHORIZATION);
        };
    }

    /** 직원 판정은 조치 요청 종류만 정한다. 접수·실행 성공이나 결제 상태 변경이 아니다. */
    public static NextStep actionForReview(State state, StaffDecision decision) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(decision, "decision");
        if (state.status() != PaymentStatus.HELD) {
            throw new IllegalStateException("직원 판정 조치는 HELD 결제에만 요청할 수 있습니다");
        }
        return switch (decision) {
            case ACCEPT -> NextStep.REQUEST_CAPTURE;
            case REJECT -> NextStep.REQUEST_VOID;
        };
    }

    private static void requireUnapprovedPending(State state) {
        Objects.requireNonNull(state, "state");
        if (state.status() != PaymentStatus.PENDING
                || state.authorizationStatus() != AuthorizationStatus.NOT_REQUESTED) {
            throw new IllegalStateException("승인 결과가 없는 PENDING 결제에만 적용할 수 있습니다");
        }
    }
}
