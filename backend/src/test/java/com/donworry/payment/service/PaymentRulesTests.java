package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.donworry.payment.entity.PaymentStatus;
import com.donworry.payment.service.PaymentRules.AuthorizationOutcome;
import com.donworry.payment.service.PaymentRules.AuthorizationStatus;
import com.donworry.payment.service.PaymentRules.FdsVerdict;
import com.donworry.payment.service.PaymentRules.NextStep;
import com.donworry.payment.service.PaymentRules.StaffDecision;
import com.donworry.payment.service.PaymentRules.State;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentRulesTests {

    @ParameterizedTest
    @CsvSource({
        "ALLOW, PENDING, REQUEST_AUTHORIZATION",
        "REVIEW, PENDING, REQUEST_AUTHORIZATION",
        "DENY, DENIED, NONE",
        "ERROR, PENDING, RECORD_FDS_ERROR"
    })
    void FDS_판정으로_승인_요청_여부를_구분한다(FdsVerdict verdict, PaymentStatus expectedStatus, NextStep expectedStep) {
        var result = PaymentRules.afterFds(State.pending(), verdict);

        assertThat(result.state().status()).isEqualTo(expectedStatus);
        assertThat(result.state().authorizationStatus()).isEqualTo(AuthorizationStatus.NOT_REQUESTED);
        assertThat(result.nextStep()).isEqualTo(expectedStep);
    }

    @ParameterizedTest
    @CsvSource({
        "ALLOW, SUCCEEDED, PENDING, SUCCEEDED, REQUEST_CAPTURE",
        "REVIEW, SUCCEEDED, HELD, SUCCEEDED, NOTIFY_HELD",
        "ALLOW, DECLINED, DECLINED, DECLINED, NONE",
        "REVIEW, DECLINED, DECLINED, DECLINED, NONE",
        "ALLOW, TECHNICAL_FAILURE, PENDING, UNKNOWN, RECONCILE_AUTHORIZATION",
        "REVIEW, TECHNICAL_FAILURE, PENDING, UNKNOWN, RECONCILE_AUTHORIZATION"
    })
    void 승인_성공과_거절과_기술_실패를_구분한다(
            FdsVerdict verdict,
            AuthorizationOutcome outcome,
            PaymentStatus expectedStatus,
            AuthorizationStatus expectedAuthorization,
            NextStep expectedStep) {
        var result = PaymentRules.afterAuthorization(State.pending(), verdict, outcome);

        assertThat(result.state().status()).isEqualTo(expectedStatus);
        assertThat(result.state().authorizationStatus()).isEqualTo(expectedAuthorization);
        assertThat(result.nextStep()).isEqualTo(expectedStep);
    }

    @ParameterizedTest
    @EnumSource(
            value = FdsVerdict.class,
            names = {"DENY", "ERROR"})
    void 차단이나_평가_실패를_승인_결과로_우회할_수_없다(FdsVerdict verdict) {
        for (var outcome : AuthorizationOutcome.values()) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> PaymentRules.afterAuthorization(State.pending(), verdict, outcome));
        }
    }

    @Test
    void ALLOW_승인_성공은_최종_매입_성공이_아니며_재승인하지_않는다() {
        var pending = State.pending();
        assertThat(PaymentRules.afterFds(pending, FdsVerdict.ALLOW).nextStep())
                .isEqualTo(NextStep.REQUEST_AUTHORIZATION);
        var approved = PaymentRules.afterAuthorization(pending, FdsVerdict.ALLOW, AuthorizationOutcome.SUCCEEDED);

        assertThat(approved.state().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(approved.nextStep()).isEqualTo(NextStep.REQUEST_CAPTURE);
        assertThatIllegalStateException().isThrownBy(() -> PaymentRules.afterFds(approved.state(), FdsVerdict.ALLOW));
        assertThatIllegalStateException()
                .isThrownBy(() -> PaymentRules.afterAuthorization(
                        approved.state(), FdsVerdict.ALLOW, AuthorizationOutcome.SUCCEEDED));
    }

    @ParameterizedTest
    @CsvSource({"ACCEPT, REQUEST_CAPTURE", "REJECT, REQUEST_VOID"})
    void HELD_직원_판정은_재승인이나_성공_상태_변경_없이_조치만_결정한다(StaffDecision decision, NextStep expectedStep) {
        var held = PaymentRules.afterAuthorization(State.pending(), FdsVerdict.REVIEW, AuthorizationOutcome.SUCCEEDED);

        assertThat(held.nextStep()).isEqualTo(NextStep.NOTIFY_HELD);
        assertThat(PaymentRules.actionForReview(held.state(), decision)).isEqualTo(expectedStep);
        assertThat(held.state()).isEqualTo(new State(PaymentStatus.HELD, AuthorizationStatus.SUCCEEDED));
    }

    @ParameterizedTest
    @EnumSource(
            value = FdsVerdict.class,
            names = {"ALLOW", "REVIEW"})
    void 승인_결과_불명에서는_재승인이나_직원_조치보다_확인이_먼저다(FdsVerdict verdict) {
        var unknown = PaymentRules.afterAuthorization(State.pending(), verdict, AuthorizationOutcome.TECHNICAL_FAILURE);

        assertThat(unknown.nextStep()).isEqualTo(NextStep.RECONCILE_AUTHORIZATION);
        assertThatIllegalStateException().isThrownBy(() -> PaymentRules.afterFds(unknown.state(), verdict));
        assertThatIllegalStateException()
                .isThrownBy(() ->
                        PaymentRules.afterAuthorization(unknown.state(), verdict, AuthorizationOutcome.SUCCEEDED));
        for (var decision : StaffDecision.values()) {
            assertThatIllegalStateException().isThrownBy(() -> PaymentRules.actionForReview(unknown.state(), decision));
        }
    }

    @ParameterizedTest
    @MethodSource("paymentsNotAwaitingAuthorization")
    void 이미_처리한_결제의_FDS와_승인_처리를_다시_시작하지_않는다(State state) {
        for (var verdict : FdsVerdict.values()) {
            assertThatIllegalStateException().isThrownBy(() -> PaymentRules.afterFds(state, verdict));
        }
        for (var verdict : new FdsVerdict[] {FdsVerdict.ALLOW, FdsVerdict.REVIEW}) {
            for (var outcome : AuthorizationOutcome.values()) {
                assertThatIllegalStateException()
                        .isThrownBy(() -> PaymentRules.afterAuthorization(state, verdict, outcome));
            }
        }
    }

    static Stream<State> paymentsNotAwaitingAuthorization() {
        return Stream.of(
                new State(PaymentStatus.PENDING, AuthorizationStatus.SUCCEEDED),
                new State(PaymentStatus.PENDING, AuthorizationStatus.UNKNOWN),
                new State(PaymentStatus.HELD, AuthorizationStatus.SUCCEEDED),
                new State(PaymentStatus.DENIED, AuthorizationStatus.NOT_REQUESTED),
                new State(PaymentStatus.DECLINED, AuthorizationStatus.DECLINED),
                new State(PaymentStatus.CAPTURED, AuthorizationStatus.SUCCEEDED),
                new State(PaymentStatus.VOIDED, AuthorizationStatus.SUCCEEDED),
                new State(PaymentStatus.EXPIRED, AuthorizationStatus.SUCCEEDED));
    }

    @ParameterizedTest
    @MethodSource("paymentsOutsideReview")
    void HELD가_아니면_직원_판정_조치를_요청하지_않는다(State state) {
        for (var decision : StaffDecision.values()) {
            assertThatIllegalStateException().isThrownBy(() -> PaymentRules.actionForReview(state, decision));
        }
    }

    static Stream<State> paymentsOutsideReview() {
        return Stream.concat(Stream.of(State.pending()), paymentsNotAwaitingAuthorization())
                .filter(state -> state.status() != PaymentStatus.HELD);
    }

    @ParameterizedTest
    @MethodSource("inconsistentStates")
    void 승인_근거가_없는_HELD나_종료_상태를_만들지_않는다(PaymentStatus status, AuthorizationStatus authorizationStatus) {
        assertThatIllegalArgumentException().isThrownBy(() -> new State(status, authorizationStatus));
    }

    static Stream<Arguments> inconsistentStates() {
        return Stream.of(
                Arguments.of(PaymentStatus.HELD, AuthorizationStatus.NOT_REQUESTED),
                Arguments.of(PaymentStatus.HELD, AuthorizationStatus.UNKNOWN),
                Arguments.of(PaymentStatus.CAPTURED, AuthorizationStatus.NOT_REQUESTED),
                Arguments.of(PaymentStatus.VOIDED, AuthorizationStatus.NOT_REQUESTED),
                Arguments.of(PaymentStatus.EXPIRED, AuthorizationStatus.NOT_REQUESTED),
                Arguments.of(PaymentStatus.DENIED, AuthorizationStatus.SUCCEEDED),
                Arguments.of(PaymentStatus.DECLINED, AuthorizationStatus.SUCCEEDED),
                Arguments.of(PaymentStatus.PENDING, AuthorizationStatus.DECLINED));
    }

    @Test
    void 누락된_입력을_정상_판정으로_간주하지_않는다() {
        assertThatNullPointerException().isThrownBy(() -> PaymentRules.afterFds(State.pending(), null));
        assertThatNullPointerException().isThrownBy(() -> PaymentRules.afterFds(null, FdsVerdict.ALLOW));
        assertThatNullPointerException()
                .isThrownBy(() -> PaymentRules.afterAuthorization(State.pending(), FdsVerdict.ALLOW, null));
        assertThatNullPointerException()
                .isThrownBy(
                        () -> PaymentRules.afterAuthorization(State.pending(), null, AuthorizationOutcome.SUCCEEDED));
        assertThatNullPointerException().isThrownBy(() -> new State(null, AuthorizationStatus.NOT_REQUESTED));
        assertThatNullPointerException().isThrownBy(() -> new State(PaymentStatus.PENDING, null));
        assertThatNullPointerException()
                .isThrownBy(() -> PaymentRules.actionForReview(
                        new State(PaymentStatus.HELD, AuthorizationStatus.SUCCEEDED), null));
    }
}
