package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.entity.MerchantApplication;
import com.donworry.identity.repository.MerchantApplicationRepository;
import com.donworry.identity.service.ApplicationRetentionService;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@Tag("mariadb")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "donworry.applications.enabled=false")
@Import(MariaDbTestContainerConfig.class)
class ApplicationDisabledHttpTests {
    @LocalServerPort
    int port;

    @Autowired
    MerchantApplicationRepository applications;

    @Autowired
    ApplicationRetentionService retention;
    // 기능 비활성은 공개 설정에 반영되며 이미 보관한 신청의 삭제를 막지 않는다.
    @Test
    void config_whenDisabled_returnsFalseWhileRetentionStillRuns() throws Exception {
        var http = HttpClient.newHttpClient();
        var uri = URI.create("http://localhost:" + port + "/api/merchant-applications/config");
        var response = http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("false");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var head = http.send(
                HttpRequest.newBuilder(uri)
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        var a = applications.saveAndFlush(MerchantApplication.submit(
                "기한경과",
                "4444444444",
                "담당",
                "disabled@example.com",
                "01011112222",
                "disabled_owner",
                null,
                "v1",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(8)));
        retention.clean();
        assertThat(applications.existsById(a.getApplicationId())).isFalse();
    }
}
