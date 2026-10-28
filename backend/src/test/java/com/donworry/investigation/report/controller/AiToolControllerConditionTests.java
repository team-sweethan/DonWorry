package com.donworry.investigation.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.donworry.investigation.report.service.AiToolService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

// AI Tool API는 서비스 키 인증 전까지 기본으로 꺼져 있어야 한다 — 로그인 없이 결제 · FDS 근거를 내주기 때문 (#254)
// DB 없이 컨트롤러 등록 조건만 본다
class AiToolControllerConditionTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AiToolService.class, () -> mock(AiToolService.class))
            .withBean(AiServerKeyGuard.class, () -> mock(AiServerKeyGuard.class))
            .withUserConfiguration(AiToolController.class);

    @Test
    void controller_isOffByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(AiToolController.class));
    }

    @Test
    void controller_isOnOnlyWhenEnabled() {
        runner.withPropertyValues("ai.tools.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(AiToolController.class));
    }
}
