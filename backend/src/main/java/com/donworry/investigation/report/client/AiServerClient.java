package com.donworry.investigation.report.client;

import com.donworry.investigation.report.dto.AiInvestigationRequest;
import com.donworry.investigation.report.dto.AiInvestigationResult;
import java.net.http.HttpClient;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

// ai-server 조사 접수 · 상태 조회 (ADR-0009 결정 1) — 연결 실패 · 5xx는 RestClientException으로 던진다
@Component
@EnableConfigurationProperties(AiServerProperties.class)
public class AiServerClient {

    // ai-server(app/client/spring.py)와 같은 헤더 이름
    public static final String API_KEY_HEADER = "X-Internal-Api-Key";

    private final RestClient rest;

    public AiServerClient(AiServerProperties properties) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        this.rest = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(API_KEY_HEADER, properties.apiKey())
                .requestFactory(factory)
                .build();
    }

    // 접수만 한다(202). 결과는 콜백 또는 status로 받는다
    public void submit(AiInvestigationRequest request) {
        rest.post()
                .uri("/investigations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    // ai-server에 기록이 없으면(재시작 등) 빈 값
    public Optional<AiInvestigationResult> status(Long reportId) {
        try {
            return Optional.ofNullable(rest.get()
                    .uri("/investigations/{reportId}", reportId)
                    .retrieve()
                    .body(AiInvestigationResult.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}
