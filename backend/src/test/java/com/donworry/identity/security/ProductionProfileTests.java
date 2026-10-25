package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.identity.service.ProductionAdminService;
import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductionProfileTests {
    private final ProductionAdminService service = mock(ProductionAdminService.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withClassLoader(new ClassLoader(getClass().getClassLoader()) {
                @Override
                public URL getResource(String name) {
                    return super.getResource(
                            name.equals("application-local.yml") ? "production-profile/application-local.yml" : name);
                }

                @Override
                public Enumeration<URL> getResources(String name) throws IOException {
                    if (name.equals("application-local.yml"))
                        return Collections.enumeration(Collections.singleton(getResource(name)));
                    return super.getResources(name);
                }
            })
            .withUserConfiguration(ProductionSecurityConfiguration.class)
            .withBean(ProductionAdminService.class, () -> service);

    private ApplicationContextRunner production() {
        return runner.withPropertyValues(
                "spring.profiles.active=prod",
                "DONWORRY_DB_HOST=production-db",
                "DONWORRY_DB_NAME=production",
                "DONWORRY_DB_USER=production-user",
                "DONWORRY_DB_PASSWORD=test-db-password",
                "DONWORRY_ADMIN_PASSWORD=Production123!");
    }

    // 프로필 없는 기존 로컬 실행은 개인 설정을 읽고 운영 초기화는 실행하지 않는다
    @Test
    void configuration_whenDefault_preservesLocalImportAndSkipsProduction() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("production-profile-test.local-imported"))
                    .isEqualTo("true");
            assertThat(context.getEnvironment().getProperty("server.servlet.session.cookie.secure"))
                    .isEqualTo("false");
            verifyNoInteractions(service);
        });
    }

    // 운영에서는 로컬 파일의 Secure=false를 읽지 않고 운영 설정과 초기화를 적용한다
    @Test
    void configuration_whenProduction_skipsLocalAndAppliesPassword() {
        production().run(context -> {
            assertThat(context).hasNotFailed();
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("production-profile-test.local-imported"))
                    .isNull();
            assertThat(environment.getProperty("server.servlet.session.cookie.secure"))
                    .isEqualTo("true");
            assertThat(environment.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:mariadb://production-db:3306/production");
            assertThat(environment.getProperty("security.csrf.enabled")).isEqualTo("false");
            verify(service).applyPassword("Production123!");
        });
    }

    // 운영과 로컬 혼용은 초기화를 실행하지 않고 기동 실패한다
    @Test
    void configuration_whenProfilesMixed_failsBeforeChangingPassword() {
        production().withPropertyValues("spring.profiles.active=prod,local").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("함께 사용할 수 없습니다");
            verifyNoInteractions(service);
        });
    }

    // 운영 DB 비밀값 누락은 입력값을 출력하지 않고 기동 실패한다
    @Test
    void configuration_whenDatabasePasswordMissing_failsBeforeChangingPassword() {
        production().withPropertyValues("DONWORRY_DB_PASSWORD=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("DONWORRY_DB_PASSWORD");
            verifyNoInteractions(service);
        });
    }

    // 관리자 비밀번호 누락은 DB 초기화 이전 단계에서 거절한다
    @Test
    void configuration_whenAdminPasswordMissing_failsBeforeChangingPassword() {
        production().withPropertyValues("DONWORRY_ADMIN_PASSWORD=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("DONWORRY_ADMIN_PASSWORD");
            verifyNoInteractions(service);
        });
    }

    // 외부 설정으로 운영 Secure 쿠키를 끄더라도 그대로 기동하지 않는다
    @Test
    void configuration_whenSecureOverridden_failsBeforeChangingPassword() {
        production()
                .withPropertyValues("server.servlet.session.cookie.secure=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    verifyNoInteractions(service);
                });
    }

    // 비밀번호 적용 실패는 삼키지 않고 기동 실패로 전파한다
    @Test
    void configuration_whenBootstrapFails_propagatesStartupFailure() {
        doThrow(new IllegalStateException("운영 초기화 실패")).when(service).applyPassword(any());
        production().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("운영 초기화 실패");
        });
    }
}
