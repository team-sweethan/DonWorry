package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.identity.api.MerchantIdentity;
import com.donworry.identity.security.SessionUser;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentMerchantServiceTests {
    private final CurrentMerchantService service = new CurrentMerchantService();

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    // 직원 세션과 익명 요청을 가맹점 인증으로 대신 사용하지 않는다
    @Test
    void findCurrent_whenStaffOrAnonymous_returnsEmpty() {
        assertThat(service.findCurrent()).isEmpty();
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        SessionUser.builder().userId(1L).build(), null, List.of()));
        assertThat(service.findCurrent()).isEmpty();
    }

    // 완료된 HMAC 인증에서만 가맹점을 공개한다
    @Test
    void findCurrent_whenMerchantAuthenticated_returnsMerchant() {
        var merchant = new MerchantIdentity(7L);
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.unauthenticated(merchant, null));
        assertThat(service.findCurrent()).isEmpty();
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(merchant, null, List.of()));
        assertThat(service.findCurrent()).contains(merchant);
    }
}
