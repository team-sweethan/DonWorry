package com.donworry.payment.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.payment.dto.*;
import com.donworry.payment.service.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.ProblemDetail;

class PaymentCancellationControllerTests {
    private final MerchantPaymentCancellationService service = mock(MerchantPaymentCancellationService.class);
    private final PaymentCancellationController controller =
            new PaymentCancellationController(mock(PaymentMerchantAccess.class), service);

    // 저장된 상태와 자기/다른 키 작업에 따라 HTTP 결과를 구분하고 조회 주소를 유지한다.
    @ParameterizedTest
    @CsvSource({
        "DEFERRED,PAYMENT_IN_PROGRESS,409",
        "REVIEW_REQUIRED,PAYMENT_REVIEW_REQUIRED,409",
        "REJECTED,PAYMENT_NOT_CANCELABLE,409",
        "PROCESSING,,202",
        "UNKNOWN,,202",
        "PROCESSING,CANCELLATION_ALREADY_IN_PROGRESS,409",
        "UNKNOWN,CANCELLATION_ALREADY_IN_PROGRESS,409",
        "FAILED,CANCELLATION_DECLINED,422",
        "FAILED,CANCELLATION_PREVIOUSLY_FAILED,409",
        "SUCCEEDED,,200",
        "ALREADY_CANCELED,,200"
    })
    void request_whenStoredResult_mapsStatusAndLocation(String state, String code, int status) {
        var key = UUID.randomUUID();
        var result = PaymentCancellationResponse.builder()
                .requestKey(key)
                .paymentId(1L)
                .requestStatus(state)
                .code(code)
                .nextAction("NONE")
                .build();
        when(service.request(1L, key, "취소")).thenReturn(result);
        var response = controller.request(1L, key.toString(), new PaymentCancellationSubmitRequest("취소"));
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getLocation().toString())
                .isEqualTo("/api/payment-cancellation-requests/" + key);
        if (status >= 400) {
            var problem = (ProblemDetail) response.getBody();
            assertThat(problem.getProperties())
                    .containsEntry("code", code)
                    .containsEntry("requestKey", key)
                    .containsEntry("requestStatus", state)
                    .containsEntry("nextAction", "NONE");
        } else {
            assertThat(response.getBody()).isSameAs(result);
        }
        assertThat(controller.find(key.toString()).getStatusCode().value()).isEqualTo(200);
        verify(service).find(key);
    }

    // 내부 장애는 원인·원문 키·가짜 저장 상태를 노출하지 않는다.
    @Test
    void error_whenInfrastructureFails_returnsSafeProblem() {
        var advice = new PaymentCancellationExceptionHandler();
        var result = advice.unavailable(new org.springframework.transaction.CannotCreateTransactionException("SECRET"));
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
        var problem = (ProblemDetail) result.getBody();
        assertThat(problem.getProperties())
                .containsEntry("code", "CANCELLATION_UNAVAILABLE")
                .doesNotContainKey("requestStatus");
        assertThat(problem.getDetail()).doesNotContain("SECRET");
        assertThat(advice.unexpected(new IllegalStateException("SECRET"))
                        .getStatusCode()
                        .value())
                .isEqualTo(500);
    }
}
