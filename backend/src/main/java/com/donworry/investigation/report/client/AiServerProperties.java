package com.donworry.investigation.report.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// ai-server 연결 설정 (application.yml ai.server) — 기본은 꺼짐: 고정 보고서를 그대로 쓴다
// 켜면 고정 보고서 대신 ai-server(Qwen)에 조사를 맡긴다. 서비스 키는 ai-server의 AI_SERVER_INTERNAL_API_KEY와 같은 값
@ConfigurationProperties(prefix = "ai.server")
public record AiServerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://localhost:8000") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        // 접수 뒤 이 시간 안에 결과가 안 오면 상태를 조회하고, 그래도 끝나지 않았으면 실패 1회로 센다
        @DefaultValue("5m") Duration resultTimeout) {}
