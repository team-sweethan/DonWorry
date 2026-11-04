package com.donworry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class SchedulingDisabledTests {
    private ApplicationContextRunner context() throws ClassNotFoundException {
        // starter-test가 가져오는 Moments는 자체 @EnableScheduling으로 앱 설정을 우회할 수 있다.
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        Class.forName("org.springframework.modulith.moments.autoconfigure.MomentsAutoConfiguration")))
                .withUserConfiguration(SchedulingConfiguration.class)
                .withPropertyValues("donworry.scheduling.enabled=false");
    }

    // 실제 테스트 JVM 설정에서는 라이브러리가 있어도 예약 작업 처리기를 등록하지 않는다
    @Test
    void testContext_whenSchedulingDisabled_doesNotRegisterScheduledProcessor() throws ClassNotFoundException {
        context().run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }

    // Moments를 켜면 앱의 예약 작업 off만으로는 스케줄러를 끌 수 없음을 재현한다
    @Test
    void testContext_whenMomentsEnabled_registersScheduledProcessorDespiteAppFlag() throws ClassNotFoundException {
        context().withPropertyValues("spring.modulith.moments.enabled=true").run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }
}
