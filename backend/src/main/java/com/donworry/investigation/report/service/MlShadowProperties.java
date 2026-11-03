package com.donworry.investigation.report.service;

import java.time.Duration;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// ML Shadow 실행 설정 (application.yml ai.ml-shadow, 설계 375 9절) — 켜기(enabled) · 주기(interval)는 MlShadowScheduler가 읽는다
// ai-server 주소 · 서비스 키 · 응답 시간 초과는 ai.server 값을 같이 쓴다
@Builder
@ConfigurationProperties(prefix = "ai.ml-shadow")
public record MlShadowProperties(
        // 한 주기에 처리할 최대 건수 (재시도 + 새 평가)
        @DefaultValue("50") int batchSize,
        // 평가 뒤 이 시간이 지난 것만 읽는다 — 늦게 커밋된 작은 평가 번호를 커서가 건너뛰지 않게
        @DefaultValue("30s") Duration settleDelay,
        @DefaultValue("3") int maxAttempts,
        // ai-server가 실제로 쓰는 모델 이름과 같아야 한다 — 다르면 기록하지 않고 멈춘다
        @DefaultValue("rf-v2") String modelVersion) {}
