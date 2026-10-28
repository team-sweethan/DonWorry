package com.donworry.investigation.report.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.investigation.report.dto.AiInvestigationRequest;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 실제 HTTP로 접수 요청을 보내 헤더를 본다 — ai-server(uvicorn)는 HTTP/2 업그레이드 요청을 400으로 거절한다
// DB 없이 JDK 내장 HTTP 서버를 ai-server 자리에 띄운다
class AiServerClientTests {

    private HttpServer server;
    private final AtomicReference<String> protocol = new AtomicReference<>();
    private final AtomicReference<Headers> headers = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/investigations", exchange -> {
            protocol.set(exchange.getProtocol());
            headers.set(exchange.getRequestHeaders());
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // HTTP/1.1로 보내고 업그레이드 헤더를 붙이지 않는다. 서비스 키는 함께 보낸다
    @Test
    void submit_usesHttp11WithoutUpgrade() {
        AiServerClient client = new AiServerClient(new AiServerProperties(
                true,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-ai-key",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofMinutes(5)));

        client.submit(new AiInvestigationRequest("1", "attempt-1", "1", 1, 1, "2", "2:9001", "2026-10-28T00:00:00Z"));

        assertThat(protocol.get()).isEqualTo("HTTP/1.1");
        assertThat(headers.get().containsKey("Upgrade")).isFalse();
        assertThat(headers.get().getFirst(AiServerClient.API_KEY_HEADER)).isEqualTo("test-ai-key");
    }
}
