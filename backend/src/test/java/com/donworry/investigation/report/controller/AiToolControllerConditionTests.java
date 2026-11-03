package com.donworry.investigation.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.donworry.investigation.report.service.AiToolService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

// AI Tool API는 ai-server를 켰을 때만 생긴다 — 꺼져 있으면 경로 자체가 없다 (#254 · ADR-0041)
// DB 없이 컨트롤러 등록 조건만 본다
class AiToolControllerConditionTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AiToolService.class, () -> mock(AiToolService.class))
            .withUserConfiguration(AiToolController.class);

    @Test
    void controller_isOffByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(AiToolController.class));
    }

    @Test
    void controller_isOnOnlyWhenEnabled() {
        runner.withPropertyValues("ai.server.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(AiToolController.class));
    }
}
