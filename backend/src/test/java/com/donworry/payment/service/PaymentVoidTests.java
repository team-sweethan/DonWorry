package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.payment.entity.Payment;
import com.donworry.payment.entity.PaymentStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentVoidTests {
    private Payment held() {
        var p = Payment.pending(1L, "customer", "order", 1000L, Instant.now());
        p.recordAuthorizationSuccess("original-auth", true);
        return p;
    }

    // 취소 완료는 원래 승인·금액을 보존하며 중복 실행 전이는 허용하지 않는다.
    @Test
    void completeVoid_whenHeld_preservesOriginalAuthorization() {
        var p = held();
        p.completeVoid();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(p.getAuthorizationRef()).isEqualTo("original-auth");
        assertThat(p.getAmount()).isEqualTo(1000L);
        assertThatIllegalStateException().isThrownBy(p::completeVoid);
    }

    // 매입된 결제를 포함해 HELD 이외의 상태를 승인 취소로 덮어쓰지 않는다.
    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = "HELD", mode = EnumSource.Mode.EXCLUDE)
    void completeVoid_whenNotHeld_rejects(PaymentStatus status) {
        var p = held();
        ReflectionTestUtils.setField(p, "status", status);
        assertThatIllegalStateException().isThrownBy(p::completeVoid);
        assertThat(p.getStatus()).isEqualTo(status);
    }

    // 승인 참조가 없는 손상된 HELD는 성공 처리하지 않는다.
    @Test
    void completeVoid_whenReferenceMissing_rejects() {
        var p = held();
        ReflectionTestUtils.setField(p, "authorizationRef", null);
        assertThatIllegalStateException().isThrownBy(p::completeVoid);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.HELD);
    }

    // 모의 취소기는 유효한 명령만 성공시키고 외부 결과 ID를 생성하지 않는다.
    @Test
    void voidAuthorization_whenValid_returnsSuccess() {
        var voider = new MockPaymentVoider();
        assertThat(voider.voidAuthorization(new AuthorizationVoidCommand(1L, "auth")))
                .isEqualTo(AuthorizationVoidResult.SUCCEEDED);
        assertThatIllegalArgumentException().isThrownBy(() -> voider.voidAuthorization(null));
        assertThatIllegalArgumentException().isThrownBy(() -> new AuthorizationVoidCommand(1L, " "));
        assertThatIllegalArgumentException().isThrownBy(() -> new AuthorizationVoidCommand(0L, "auth"));
    }
}
