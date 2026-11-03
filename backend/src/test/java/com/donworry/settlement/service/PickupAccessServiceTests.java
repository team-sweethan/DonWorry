package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.identity.api.CurrentMerchant;
import com.donworry.identity.api.MerchantIdentity;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class PickupAccessServiceTests {
    // 인증 제공자가 없으면 운영 요청을 임의 가맹점으로 처리하지 않는다.
    @Test
    void requireMerchant_whenProviderMissing_returnsUnavailable() {
        var service = new PickupAccessService(new StaticListableBeanFactory().getBeanProvider(CurrentMerchant.class));
        assertThatThrownBy(service::requireMerchant)
                .isInstanceOfSatisfying(
                        SettlementRequestException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(503));
    }

    // 직원 로그인 여부와 무관하게 서버 간 인증 결과가 없으면 거절한다.
    @Test
    void requireMerchant_whenNotAuthenticated_returnsUnauthorized() {
        var identity = mock(CurrentMerchant.class);
        when(identity.findCurrent()).thenReturn(Optional.empty());
        var beans = new StaticListableBeanFactory();
        beans.addBean("currentMerchant", identity);
        var service = new PickupAccessService(beans.getBeanProvider(CurrentMerchant.class));
        assertThatThrownBy(service::requireMerchant)
                .isInstanceOfSatisfying(
                        SettlementRequestException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(401));
    }

    // P1이 서명 검증으로 확인한 가맹점 ID만 반환한다.
    @Test
    void requireMerchant_whenAuthenticated_returnsSignedMerchant() {
        var identity = mock(CurrentMerchant.class);
        when(identity.findCurrent()).thenReturn(Optional.of(new MerchantIdentity(7L)));
        var beans = new StaticListableBeanFactory();
        beans.addBean("currentMerchant", identity);
        assertThat(new PickupAccessService(beans.getBeanProvider(CurrentMerchant.class)).requireMerchant())
                .isEqualTo(7L);
    }
}
