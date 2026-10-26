package com.donworry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 예약 작업은 앱 전체 설정이라 특정 모듈이 아닌 여기서 켠다. 테스트는 끄고 작업을 직접 호출한다.
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "donworry.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfiguration {}
