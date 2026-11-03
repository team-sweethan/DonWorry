package com.donworry.investigation.report.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.fds.api.FdsMlInput;
import com.donworry.fds.api.FdsMlTransaction;
import com.donworry.investigation.report.dto.MlFraudScoreRequest;
import com.donworry.investigation.report.dto.MlFraudScoreResponse;
import com.donworry.investigation.report.entity.MlRiskLevel;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

// ML 채점 호출을 실제 HTTP로 확인한다 (#414) — JDK 내장 HTTP 서버를 ai-server 자리에 띄운다 (DB 없음)
class AiServerClientScoreTests {

    // ai-server FraudScoreResponse.model_dump_json() 모양 — threshold는 학습 코드 부동소수 오차 그대로
    private static final String SCORED = """
            {"evaluationId":31,"modelVersion":"rf-v1","fraudProbability":0.66,"threshold":0.30000000000000004,
             "mlRiskLevel":"HIGH","features":{"amount":30000,"amountRatio":1.5,"cardSeenBefore":0},"latencyMs":27}
            """;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private final AtomicReference<String> sentBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = SCORED;
    private volatile long delayMillis = 0;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ml/fraud-score", exchange -> {
            sentBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // P2 묶음을 ai-server 키 이름으로 바꿔 보낸다 — 별칭은 그대로, 시각은 Z가 붙고, 기기 없음은 null
    @Test
    void score_request_matchesAiServerContract() {
        FdsMlInput input = FdsMlInput.builder()
                .target(transaction(101L, "T", Instant.parse("2026-11-02T05:00:00Z"), null, "C1"))
                .customerHistory(List.of(transaction(100L, "T", Instant.parse("2026-11-01T05:00:00Z"), "D1", "C1")))
                .sharedCardHistory(List.of(transaction(90L, "U", Instant.parse("2026-10-31T05:00:00Z"), "D2", "C1")))
                .historyOverLimit(false)
                .build();

        client(Duration.ofSeconds(5)).score(MlFraudScoreRequest.from(31L, input));

        // 키 순서는 Jackson 설정에 따라 다를 수 있어 JSON 트리로 비교한다
        assertThat(JSON.readTree(sentBody.get())).isEqualTo(JSON.readTree("""
                {"evaluationId":31,
                 "target":{"paymentId":101,"merchantId":7,"customerRef":"T","amount":30000,
                           "paymentRequestedAt":"2026-11-02T05:00:00Z","deviceId":null,"cardToken":"C1"},
                 "customerHistory":[{"paymentId":100,"merchantId":7,"customerRef":"T","amount":30000,
                           "paymentRequestedAt":"2026-11-01T05:00:00Z","deviceId":"D1","cardToken":"C1"}],
                 "sharedCardHistory":[{"paymentId":90,"merchantId":7,"customerRef":"U","amount":30000,
                           "paymentRequestedAt":"2026-10-31T05:00:00Z","deviceId":"D2","cardToken":"C1"}]}
                """));
    }

    // 200 응답 값을 그대로 옮긴다 — 반올림 · 등급 재계산 없이
    @Test
    void score_ok_returnsScoredValues() {
        MlScoreOutcome outcome = client(Duration.ofSeconds(5)).score(request());

        assertThat(outcome)
                .isEqualTo(new MlScoreOutcome.Scored(MlFraudScoreResponse.builder()
                        .evaluationId(31L)
                        .modelVersion("rf-v1")
                        .fraudProbability(0.66)
                        .threshold(0.30000000000000004)
                        .mlRiskLevel(MlRiskLevel.HIGH)
                        .features(Map.of("amount", 30000, "amountRatio", 1.5, "cardSeenBefore", 0))
                        .latencyMs(27)
                        .build()));
    }

    // 응답 코드를 설계 8절 표대로 나눈다 — 401은 서비스 키 설정 문제라 결제 실패로 굳히지 않는다
    @ParameterizedTest
    @MethodSource("errorStatuses")
    void score_errorStatus_classifiedAsDesignTable(int errorStatus, String body, MlScoreOutcome expected) {
        status = errorStatus;
        responseBody = body;

        assertThat(client(Duration.ofSeconds(5)).score(request())).isEqualTo(expected);
    }

    static Stream<Arguments> errorStatuses() {
        return Stream.of(
                Arguments.of(
                        422,
                        "{\"detail\":[{\"type\":\"value_error\"}]}",
                        new MlScoreOutcome.Rejected("ML_REQUEST_REJECTED")),
                Arguments.of(
                        500,
                        "{\"detail\":\"ML_INFERENCE_FAILED\"}",
                        new MlScoreOutcome.Retryable("ML_INFERENCE_FAILED")),
                Arguments.of(503, "{\"detail\":\"ML_MODEL_UNAVAILABLE\"}", new MlScoreOutcome.Unavailable("HTTP_503")),
                Arguments.of(401, "{\"detail\":\"서비스 키가 올바르지 않습니다\"}", new MlScoreOutcome.Unavailable("HTTP_401")));
    }

    // ai-server가 꺼져 있으면 서버 사용 불가 — 결제마다 실패로 기록하지 않게
    @Test
    void score_connectionRefused_unavailable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }

        assertThat(client(closedPort, Duration.ofSeconds(5)).score(request()))
                .isInstanceOf(MlScoreOutcome.Unavailable.class);
    }

    // 응답이 늦으면 다시 해볼 실패 — 연결 실패와 구분한다
    @Test
    void score_readTimeout_retryable() {
        delayMillis = 1_000;

        assertThat(client(Duration.ofMillis(200)).score(request()))
                .isEqualTo(new MlScoreOutcome.Retryable("ML_TIMEOUT"));
    }

    private MlFraudScoreRequest request() {
        return MlFraudScoreRequest.from(
                31L,
                FdsMlInput.builder()
                        .target(transaction(101L, "T", Instant.parse("2026-11-02T05:00:00Z"), "D1", "C1"))
                        .customerHistory(List.of())
                        .sharedCardHistory(List.of())
                        .historyOverLimit(false)
                        .build());
    }

    private static FdsMlTransaction transaction(
            Long paymentId, String customerKey, Instant requestedAt, String deviceKey, String cardKey) {
        return FdsMlTransaction.builder()
                .paymentId(paymentId)
                .merchantId(7L)
                .customerKey(customerKey)
                .amount(30_000L)
                .paymentRequestedAt(requestedAt)
                .deviceKey(deviceKey)
                .cardKey(cardKey)
                .build();
    }

    private AiServerClient client(Duration readTimeout) {
        return client(server.getAddress().getPort(), readTimeout);
    }

    private static AiServerClient client(int port, Duration readTimeout) {
        return new AiServerClient(new AiServerProperties(
                true,
                "http://127.0.0.1:" + port,
                "test-ai-key",
                Duration.ofSeconds(2),
                readTimeout,
                Duration.ofMinutes(5)));
    }
}
