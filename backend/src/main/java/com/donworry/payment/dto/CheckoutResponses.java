package com.donworry.payment.dto;

import com.donworry.payment.entity.PaymentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

// 공개 결제창 화면용 요청·응답. 로그인 없이 세션 키로만 열리므로 가맹점 내부 정보(고객 참조, 알림 주소)는 내보내지 않는다.
public final class CheckoutResponses {
    private CheckoutResponses() {}

    public record Card(String code, String label, String masked, String issuerCountry, String color) {}

    // inProgress면 결제창이 다시 조회한다. 그 밖에는 redirectUrl로 가맹점에 돌려보낸다.
    public record Result(PaymentStatus paymentStatus, boolean authorized, boolean inProgress, String redirectUrl) {}

    public record Session(
            String merchantName,
            String orderRef,
            String orderName,
            Long amount,
            String buyerEmail,
            Instant expiresAt,
            boolean expired,
            List<Card> cards,
            Result result,
            String cancelUrl,
            // 결제창이 레이어(iframe)로 열렸을 때 높이·결과를 알릴 가맹점 출처(성공 URL의 출처)
            String returnOrigin) {}

    public record PayRequest(
            @NotBlank @Pattern(regexp = "[A-Z]") String card,

            @Size(max = 100) @Pattern(regexp = "[0-9a-fA-F-]{8,100}")
            String deviceId) {}
}
