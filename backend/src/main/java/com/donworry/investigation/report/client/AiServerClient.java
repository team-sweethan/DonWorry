package com.donworry.investigation.report.client;

import com.donworry.investigation.report.dto.AiInvestigationRequest;
import com.donworry.investigation.report.dto.AiInvestigationResult;
import com.donworry.investigation.report.dto.MlFraudScoreRequest;
import com.donworry.investigation.report.dto.MlFraudScoreResponse;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// ai-server 조사 접수 · 상태 조회 (ADR-0009 결정 1) — 연결 실패 · 5xx는 RestClientException으로 던진다
// ML 채점(score)만은 예외 대신 결과 종류(MlScoreOutcome)로 돌려준다 (#414)
@Component
@EnableConfigurationProperties(AiServerProperties.class)
public class AiServerClient {

    // ai-server(app/client/spring.py)와 같은 헤더 이름
    public static final String API_KEY_HEADER = "X-Internal-Api-Key";

    private final RestClient rest;

    public AiServerClient(AiServerProperties properties) {
        // JDK 기본값은 HTTP/2 업그레이드(Upgrade: h2c) 요청 — ai-server(uvicorn)는 이를 400으로 거절해 HTTP/1.1로 고정한다
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
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

    // ML Shadow 채점 (#414) — 설계 8절 표대로 나눠 돌려주고 예외를 던지지 않는다
    // 이력 상한 확인 · 모델 버전 비교는 부르는 쪽(#415)이 한다
    public MlScoreOutcome score(MlFraudScoreRequest request) {
        try {
            MlFraudScoreResponse response = rest.post()
                    .uri("/ml/fraud-score")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(MlFraudScoreResponse.class);
            if (response == null) {
                return new MlScoreOutcome.Unavailable("RESPONSE_INVALID");
            }
            return new MlScoreOutcome.Scored(response);
        } catch (RestClientResponseException e) {
            return classifyStatus(e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            return classifyIoFailure(e);
        } catch (RestClientException e) {
            // 200인데 본문을 읽지 못함 — 계약이 어긋나면 모든 결제가 같으므로 결제 실패로 굳히지 않는다
            return new MlScoreOutcome.Unavailable("RESPONSE_INVALID");
        }
    }

    // 401 · 403 · 404는 서비스 키 · 경로 설정 문제, 500 말고 5xx(502 · 503 · 504)는 서버 문제라 결제 탓이 아니다
    private static MlScoreOutcome classifyStatus(int status) {
        if (status == 500) {
            return new MlScoreOutcome.Retryable(MlScoreOutcome.INFERENCE_FAILED);
        }
        if (status == 401 || status == 403 || status == 404 || status >= 500) {
            return new MlScoreOutcome.Unavailable("HTTP_" + status);
        }
        return new MlScoreOutcome.Rejected(MlScoreOutcome.REQUEST_REJECTED);
    }

    // JDK에서는 연결 시간 초과가 응답 시간 초과의 하위 타입이라 먼저 본다
    private static MlScoreOutcome classifyIoFailure(ResourceAccessException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException) {
                break;
            }
            if (cause instanceof HttpTimeoutException) {
                return new MlScoreOutcome.Retryable(MlScoreOutcome.TIMEOUT);
            }
        }
        return new MlScoreOutcome.Unavailable("CONNECTION_FAILED");
    }
}
