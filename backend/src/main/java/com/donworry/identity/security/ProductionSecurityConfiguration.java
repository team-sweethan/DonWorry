package com.donworry.identity.security;

import com.donworry.identity.service.ProductionAdminService;
import java.util.Arrays;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionSecurityConfiguration {
    @Bean
    static BeanFactoryPostProcessor productionConfigurationGuard(Environment environment) {
        // 잘못된 프로필·환경변수는 데이터소스 생성과 Flyway 실행보다 먼저 거절한다.
        return beanFactory -> {
            if (Arrays.asList(environment.getActiveProfiles()).contains("local"))
                throw new IllegalStateException("prod와 local 프로필은 함께 사용할 수 없습니다.");
            for (String name :
                    new String[] {"DONWORRY_DB_HOST", "DONWORRY_DB_NAME", "DONWORRY_DB_USER", "DONWORRY_DB_PASSWORD"}) {
                if (environment.getProperty(name, "").isBlank())
                    throw new IllegalStateException("운영 DB 환경변수가 필요합니다: " + name);
            }
            if (!environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false))
                throw new IllegalStateException("운영 세션 쿠키는 Secure 설정이 필요합니다.");
            if (environment.getProperty("DONWORRY_ADMIN_PASSWORD", "").isBlank())
                throw new IllegalStateException("DONWORRY_ADMIN_PASSWORD가 필요합니다.");
        };
    }

    @Bean
    @Lazy(false)
    @DependsOnDatabaseInitialization
    SmartInitializingSingleton productionAdminInitializer(Environment environment, ProductionAdminService service) {
        // 웹 서버의 lifecycle 시작 전에 커밋을 끝낸다. 실패는 컨텍스트 기동 실패로 전파한다.
        return () -> {
            service.applyPassword(environment.getProperty("DONWORRY_ADMIN_PASSWORD"));
        };
    }
}
