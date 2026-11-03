package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ApplicationSettingsTests {
    private ApplicationSettings settings(boolean enabled, String url, boolean auth, boolean tls) {
        return new ApplicationSettings(
                enabled,
                url,
                "team@example.com",
                "smtp.gmail.com",
                "team@example.com",
                "test-only",
                auth,
                tls,
                new MockEnvironment().withProperty("spring.profiles.active", "prod"));
    }
    // 운영 신청을 켜려면 HTTPS·SMTP 인증·필수 TLS가 모두 있어야 한다.
    @Test
    void production_whenTransportUnsafe_rejectsConfiguration() {
        assertThatThrownBy(() -> settings(true, "http://service.example.com", true, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(true, "https://service.example.com", false, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(true, "https://service.example.com", true, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(true, "https://localhost", true, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(true, "https://service.example.com?redirect=evil", true, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
    // 공개 URL은 요청 Host와 무관하며 기능을 끈 운영은 메일 설정 없이 기동할 수 있다.
    @Test
    void production_whenConfigured_buildsFixedOriginLink() {
        assertThat(settings(true, "https://service.example.com/", true, true).verificationLink("test-token"))
                .isEqualTo("https://service.example.com/apply/verify#token=test-token");
        assertThatThrownBy(() -> settings(false, "", false, false).requireEnabled())
                .isInstanceOf(com.donworry.identity.security.ApplicationException.class);
    }
}
