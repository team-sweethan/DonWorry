package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.fds.api.FdsAuthorizationResultRecorder;
import com.donworry.fds.api.FdsEvaluationResponse;
import com.donworry.fds.api.FdsResult;
import com.donworry.payment.api.PaymentHeld;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.entity.PaymentStatus;
import com.donworry.payment.repository.PaymentRepository;
import com.donworry.settlement.api.CaptureRequested;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentAuthorizationServiceTests {
    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final PaymentAuthorizer authorizer = mock(PaymentAuthorizer.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final FdsAuthorizationResultRecorder authorizationResults = mock(FdsAuthorizationResultRecorder.class);
    private final PaymentAuthorizationService service =
            new PaymentAuthorizationService(repository, authorizer, events, authorizationResults);
    private final Payment payment =
            Payment.pending(1L, "customer", "order", 1000L, Instant.parse("2026-10-23T00:00:00Z"));

    private PaymentAuthorizationContext context(FdsResult verdict) {
        ReflectionTestUtils.setField(payment, "paymentId", 10L);
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(payment));
        var requestId = UUID.randomUUID();
        var response = FdsEvaluationResponse.builder()
                .evaluationRequestId(requestId)
                .evaluationId(20L)
                .result(verdict)
                .riskScore(verdict == FdsResult.ERROR ? null : 50)
                .errorCode(verdict == FdsResult.ERROR ? "FDS_ERROR" : null)
                .evaluatedAt(payment.getCreatedAt().plusSeconds(1))
                .build();
        // 잘못된 사전 Decision도 실제 평가와 현재 상태로 다시 판단해야 한다.
        var evaluation = new PaymentEvaluationResult(
                response, new PaymentRules.Decision(PaymentRules.State.pending(), PaymentRules.NextStep.NONE));
        return new PaymentAuthorizationContext(PaymentCreationSnapshot.from(payment), requestId, evaluation);
    }

    // 성공이면 저장 결제 값으로 승인하고 상태·참조와 후속 판단을 함께 반환한다.
    @ParameterizedTest
    @EnumSource(
            value = FdsResult.class,
            names = {"ALLOW", "REVIEW"})
    void authorize_whenSuccessful_updatesStateAndReference(FdsResult verdict) {
        var context = context(verdict);
        var command = new AuthorizationCommand(10L, 1000L);
        when(authorizer.authorize(command))
                .thenReturn(new AuthorizationResult(AuthorizationResult.Type.SUCCEEDED, "auth-10", null));
        var before = Instant.now();
        var result = service.authorize(context);
        var after = Instant.now();
        assertThat(payment.getStatus())
                .isEqualTo(verdict == FdsResult.ALLOW ? PaymentStatus.PENDING : PaymentStatus.HELD);
        assertThat(payment.getAuthorizationRef()).isEqualTo("auth-10");
        assertThat(result.status()).isEqualTo(payment.getStatus());
        assertThat(result.authorizationRef()).isEqualTo("auth-10");
        assertThat(result.evaluationResult()).isSameAs(context.evaluationResult());
        assertThat(result.nextStep())
                .isEqualTo(
                        verdict == FdsResult.ALLOW
                                ? PaymentRules.NextStep.REQUEST_CAPTURE
                                : PaymentRules.NextStep.NOTIFY_HELD);
        assertThat(result.declineCode()).isNull();
        assertThatThrownBy(() -> service.authorize(context)).isInstanceOf(IllegalStateException.class);
        verify(authorizer).authorize(command);
        verifyNoMoreInteractions(authorizer);
        verify(authorizationResults).record(20L, FdsAuthorizationResultRecorder.AuthorizationResult.APPROVED);
        if (verdict == FdsResult.ALLOW) {
            verify(events).publishEvent(new CaptureRequested(10L, 1L, "order", 1000L));
            verifyNoMoreInteractions(events);
        } else {
            var held = ArgumentCaptor.forClass(PaymentHeld.class);
            verify(events).publishEvent(held.capture());
            assertThat(held.getValue())
                    .isEqualTo(new PaymentHeld(
                            10L,
                            1L,
                            "customer",
                            1000L,
                            "auth-10",
                            20L,
                            held.getValue().heldAt()));
            assertThat(held.getValue().heldAt()).isBetween(before, after);
            verifyNoMoreInteractions(events);
        }
    }

    // 업무 거절을 기술 오류와 구분하고 중복 호출에서 다시 승인하지 않는다.
    @ParameterizedTest
    @EnumSource(
            value = FdsResult.class,
            names = {"ALLOW", "REVIEW"})
    void authorize_whenDeclined_recordsDeclineWithoutReference(FdsResult verdict) {
        var context = context(verdict);
        when(authorizer.authorize(any()))
                .thenReturn(new AuthorizationResult(AuthorizationResult.Type.DECLINED, null, "MOCK_DECLINED"));
        var result = service.authorize(context);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(payment.getAuthorizationRef()).isNull();
        assertThat(result.declineCode()).isEqualTo("MOCK_DECLINED");
        assertThat(result.nextStep()).isEqualTo(PaymentRules.NextStep.NONE);
        assertThatThrownBy(() -> service.authorize(context)).isInstanceOf(IllegalStateException.class);
        verify(authorizer).authorize(any());
        verify(authorizationResults).record(20L, FdsAuthorizationResultRecorder.AuthorizationResult.DECLINED);
        verifyNoInteractions(events);
    }

    // 차단과 평가 오류에서는 승인기를 호출하지 않는다.
    @ParameterizedTest
    @EnumSource(
            value = FdsResult.class,
            names = {"DENY", "ERROR"})
    void authorize_whenNoAuthorizationRequired_skipsAuthorizer(FdsResult verdict) {
        var context = context(verdict);
        var result = service.authorize(context);
        assertThat(result.status()).isEqualTo(verdict == FdsResult.DENY ? PaymentStatus.DENIED : PaymentStatus.PENDING);
        assertThat(result.nextStep())
                .isEqualTo(
                        verdict == FdsResult.DENY
                                ? PaymentRules.NextStep.NONE
                                : PaymentRules.NextStep.RECORD_FDS_ERROR);
        assertThat(payment.getAuthorizationRef()).isNull();
        verifyNoInteractions(authorizer, events);
    }

    // 모의 기술 실패와 빈 응답은 성공·거절로 변환하거나 자동 재시도하지 않는다.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void authorize_whenAuthorizerFails_propagatesWithoutStateChange(boolean throwsException) {
        var context = context(FdsResult.ALLOW);
        if (throwsException) {
            when(authorizer.authorize(any())).thenThrow(new IllegalStateException("unavailable"));
        }
        assertThatThrownBy(() -> service.authorize(context)).isInstanceOf(IllegalStateException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getAuthorizationRef()).isNull();
        verify(authorizer).authorize(any());
        verifyNoInteractions(events);
    }

    // 처리된 모든 상태는 평가 분기와 무관하게 승인 전에 거절한다.
    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "PENDING")
    void authorize_whenAlreadyProcessed_rejectsBeforeAuthorization(PaymentStatus status) {
        var context = context(FdsResult.ERROR);
        ReflectionTestUtils.setField(payment, "status", status);
        assertThatThrownBy(() -> service.authorize(context)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(authorizer, events);
    }

    // 스냅샷과 저장 결제의 식별·고객·금액·시각이 다르면 승인하지 않는다.
    @ParameterizedTest
    @ValueSource(strings = {"paymentId", "customerRef", "amount", "createdAt"})
    void authorize_whenSnapshotMismatch_rejectsBeforeAuthorization(String field) {
        var context = context(FdsResult.ALLOW);
        Object changed = switch (field) {
            case "paymentId", "amount" -> 999L;
            case "customerRef" -> "different";
            default -> payment.getCreatedAt().plusSeconds(1);
        };
        ReflectionTestUtils.setField(payment, field, changed);
        assertThatThrownBy(() -> service.authorize(context)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(authorizer, events);
    }

    // 없는 결제는 승인하지 않는다.
    @Test
    void authorize_whenPaymentMissing_rejectsBeforeAuthorization() {
        var context = context(FdsResult.ALLOW);
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.authorize(context)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(authorizer, events);
    }

    // 발행 오류는 성공으로 숨기거나 재시도하지 않고 트랜잭션 경계로 전달한다.
    @ParameterizedTest
    @EnumSource(
            value = FdsResult.class,
            names = {"ALLOW", "REVIEW"})
    void authorize_whenPublicationFails_propagatesWithoutRetry(FdsResult verdict) {
        var context = context(verdict);
        when(authorizer.authorize(any()))
                .thenReturn(new AuthorizationResult(AuthorizationResult.Type.SUCCEEDED, "auth-10", null));
        var failure = new IllegalStateException("publication unavailable");
        doThrow(failure).when(events).publishEvent(any(Object.class));
        assertThatThrownBy(() -> service.authorize(context)).isSameAs(failure);
        if (verdict == FdsResult.ALLOW) {
            verify(events).publishEvent(new CaptureRequested(10L, 1L, "order", 1000L));
        } else {
            verify(events).publishEvent(any(PaymentHeld.class));
        }
        verifyNoMoreInteractions(events);
    }

    // 요청 UUID가 다른 평가로 승인 문맥을 만들 수 없다.
    @Test
    void context_whenRequestMismatch_rejectsConstruction() {
        var context = context(FdsResult.ALLOW);
        assertThatThrownBy(() -> new PaymentAuthorizationContext(
                        context.payment(), UUID.randomUUID(), context.evaluationResult()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
