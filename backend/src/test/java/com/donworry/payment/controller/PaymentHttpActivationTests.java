package com.donworry.payment.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.identity.api.TenantReader;
import com.donworry.payment.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PaymentHttpActivationTests {
    // 운영 기본값에서는 HTTP 컨트롤러가 등록되지 않는다.
    @Test
    void controller_withoutFlag_isAbsent() {
        new ApplicationContextRunner()
                .withUserConfiguration(PaymentHttpController.class)
                .withBean(PaymentMerchantAccess.class, () -> mock(PaymentMerchantAccess.class))
                .withBean(PaymentHttpService.class, () -> mock(PaymentHttpService.class))
                .run(context -> assertThat(context).doesNotHaveBean(PaymentHttpController.class));
    }
    // 설정을 켜더라도 인증 어댑터가 없으면 가맹점을 추정하지 않고 차단한다.
    @Test
    void access_withoutAdapter_failsClosed() {
        var factory = new DefaultListableBeanFactory();
        var tenants = mock(TenantReader.class);
        var access = new PaymentMerchantAccess(factory.getBeanProvider(PaymentMerchantIdentity.class), tenants);
        assertThatThrownBy(() -> access.requireMerchant(true)).isInstanceOfSatisfying(PaymentHttpException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(503);
            assertThat(e.getCode()).isEqualTo("PAYMENT_AUTH_NOT_READY");
        });
        assertThatThrownBy(() -> access.requireMerchant(false)).isInstanceOf(PaymentHttpException.class);
        verifyNoInteractions(tenants);
    }
}
