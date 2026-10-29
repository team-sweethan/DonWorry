package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.dto.MerchantApplicationRequests.SubmitRequest;
import com.donworry.identity.entity.ApplicationStatus;
import com.donworry.identity.repository.MerchantApplicationRepository;
import java.net.URI;
import java.net.http.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class ApplicationSmtpMariaDbTests {
    static final GenericContainer<?> MAILPIT =
            new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.27.10")).withExposedPorts(1025, 8025);

    static {
        MAILPIT.start();
    }

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
        registry.add("spring.mail.username", () -> "");
        registry.add("spring.mail.password", () -> "");
        registry.add("spring.mail.properties[mail.smtp.auth]", () -> "false");
        registry.add("spring.mail.properties[mail.smtp.starttls.enable]", () -> "false");
        registry.add("spring.mail.properties[mail.smtp.starttls.required]", () -> "false");
    }

    @Autowired
    MerchantApplicationService service;

    @Autowired
    MerchantApplicationRepository applications;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper mapper;

    @Autowired
    ApplicationMailSender sender;

    // 실제 Mailpit SMTP에서 받은 한국어 메일과 링크로 이메일 확인을 완료한다.
    @Test
    void smtp_whenUsingMailpit_deliversRealMessageAndVerifiableLink() throws Exception {
        var request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.0.2.250");
        var input = SubmitRequest.builder()
                .merchantName("SMTP 가맹점")
                .businessRegistrationNumber("9876543210")
                .contactName("담당자")
                .email("smtp@example.com")
                .phone("01012345678")
                .consent(true)
                .consentVersion("2026-10-29")
                .build();
        try {
            assertThat(service.submit(input, request).mailStatus()).isEqualTo("SENT");
            String base = "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025);
            var http = HttpClient.newHttpClient();
            var list = mapper.readTree(http.send(
                            HttpRequest.newBuilder(URI.create(base + "/api/v1/messages"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString())
                    .body());
            assertThat(list.get("messages").size()).isEqualTo(1);
            String id = list.get("messages").get(0).get("ID").asString();
            var message = mapper.readTree(http.send(
                            HttpRequest.newBuilder(URI.create(base + "/api/v1/message/" + id))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString())
                    .body());
            assertThat(message.get("Subject").asString()).contains("돈워리", "이메일");
            String text = message.get("Text").asString();
            assertThat(text)
                    .contains("24시간", "결제 연동은 별도", "/apply/verify#token=")
                    .doesNotContain("임시 비밀번호");
            var token = Pattern.compile("#token=([A-Za-z0-9_-]{43})").matcher(text);
            assertThat(token.find()).isTrue();
            service.verify(new com.donworry.identity.dto.MerchantApplicationRequests.VerifyRequest(token.group(1)));
            var app = applications.findAll().stream()
                    .filter(a -> a.getEmail().equals("smtp@example.com"))
                    .findFirst()
                    .orElseThrow();
            assertThat(app.getStatus()).isEqualTo(ApplicationStatus.REVIEW_PENDING);
            sender.sendApproval(
                    "smtp@example.com", "smtp_owner", "http://localhost:5173/apply/password#token=" + "A".repeat(43));
            sender.sendRejection("smtp@example.com", "사업자등록번호를 다시 확인해 주세요.");
            var reviewedMessages = mapper.readTree(http.send(
                                    HttpRequest.newBuilder(URI.create(base + "/api/v1/messages"))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .body())
                    .get("messages");
            assertThat(reviewedMessages.size()).isEqualTo(3);
            boolean approvalSeen = false;
            boolean rejectionSeen = false;
            for (var entry : reviewedMessages) {
                var received = mapper.readTree(http.send(
                                HttpRequest.newBuilder(URI.create(base + "/api/v1/message/"
                                                + entry.get("ID").asString()))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString())
                        .body());
                String subject = received.get("Subject").asString();
                String body = received.get("Text").asString();
                if (subject.contains("이용 승인")) {
                    approvalSeen = true;
                    assertThat(body)
                            .contains("smtp_owner", "/apply/password#token=", "72시간", "결제 연동은 별도")
                            .doesNotContain("임시 비밀번호");
                }
                if (subject.contains("검토 결과")) {
                    rejectionSeen = true;
                    assertThat(body).contains("사업자등록번호를 다시 확인해 주세요.", "계정은 발급되지", "/apply");
                }
            }
            assertThat(approvalSeen).isTrue();
            assertThat(rejectionSeen).isTrue();
        } finally {
            var ids = jdbc.queryForList(
                    "SELECT application_id FROM merchant_applications WHERE email='smtp@example.com'", Long.class);
            for (Long id : ids) {
                jdbc.update("DELETE FROM application_mail_deliveries WHERE application_id=?", id);
                jdbc.update("DELETE FROM application_tokens WHERE application_id=?", id);
                jdbc.update("DELETE FROM merchant_applications WHERE application_id=?", id);
            }
        }
    }
}
