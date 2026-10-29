package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.dto.MerchantApplicationRequests.*;
import java.net.*;
import java.net.http.*;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MariaDbTestContainerConfig.class)
class ApplicationRecoverySmtpMariaDbTests {
    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        ApplicationSmtpMariaDbTests.mailProperties(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MerchantApplicationService applications;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JsonMapper mapper;

    private final CookieManager cookies = new CookieManager();
    private final HttpClient client =
            HttpClient.newBuilder().cookieHandler(cookies).build();

    private String mailBase() {
        return "http://" + ApplicationSmtpMariaDbTests.MAILPIT.getHost() + ":"
                + ApplicationSmtpMariaDbTests.MAILPIT.getMappedPort(8025);
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (!method.equals("GET"))
            cookies.getCookieStore().getCookies().stream()
                    .filter(c -> c.getName().equals("XSRF-TOKEN"))
                    .findFirst()
                    .ifPresent(c -> builder.header("X-XSRF-TOKEN", c.getValue()));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String text(String subject) throws Exception {
        var list = mapper.readTree(client.send(
                                HttpRequest.newBuilder(URI.create(mailBase() + "/api/v1/messages"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString())
                        .body())
                .get("messages");
        for (var entry : list) {
            var message = mapper.readTree(client.send(
                            HttpRequest.newBuilder(URI.create(mailBase() + "/api/v1/message/"
                                            + entry.get("ID").asString()))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString())
                    .body());
            if (message.get("Subject").asString().contains(subject))
                return message.get("Text").asString();
        }
        throw new AssertionError("expected SMTP message missing");
    }

    private String token(String text) {
        var matcher = Pattern.compile("#token=([A-Za-z0-9_-]{43})").matcher(text);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private long submit(String email, String business, String login) {
        var request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.0.2.201");
        assertThat(applications
                        .submit(
                                SubmitRequest.builder()
                                        .merchantName("SMTP 복구")
                                        .businessRegistrationNumber(business)
                                        .contactName("담당")
                                        .email(email)
                                        .phone("01011112222")
                                        .requestedLoginId(login)
                                        .consent(true)
                                        .consentVersion("2026-10-29.1")
                                        .build(),
                                request)
                        .mailStatus())
                .isEqualTo("SENT");
        return jdbc.queryForObject("SELECT application_id FROM merchant_applications WHERE email=?", Long.class, email);
    }

    private long latest(long id) {
        return jdbc.queryForObject(
                "SELECT MAX(delivery_id) FROM application_mail_deliveries WHERE application_id=?", Long.class, id);
    }

    private void allow(long id) {
        jdbc.update(
                "UPDATE application_mail_deliveries SET started_at=UTC_TIMESTAMP(6)-INTERVAL 6 MINUTE WHERE application_id=?",
                id);
        jdbc.update(
                "UPDATE application_rate_limits SET attempt_times='0' WHERE subject_hash=SHA2('smtp-recovery@example.com',256)");
    }

    private HttpResponse<String> resend(long id) throws Exception {
        return request(
                "POST",
                "/api/admin/merchant-applications/" + id + "/mail-resends",
                mapper.writeValueAsString(Map.of("expectedDeliveryId", latest(id))));
    }

    @BeforeEach
    void prepare() throws Exception {
        client.send(
                HttpRequest.newBuilder(URI.create(mailBase() + "/api/v1/messages"))
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        jdbc.update(
                "INSERT INTO platform_users(login_id,password_hash,display_name,status,role,created_at,password_change_required) VALUES('smtp_recovery_admin',?,'SMTP 관리자','ACTIVE','ADMIN',UTC_TIMESTAMP(6),false)",
                encoder.encode("SmtpAdmin123!"));
        request("GET", "/api/auth/csrf", null);
        assertThat(request(
                                "POST",
                                "/api/auth/login",
                                "{\"loginId\":\"smtp_recovery_admin\",\"password\":\"SmtpAdmin123!\"}")
                        .statusCode())
                .isEqualTo(200);
        request("GET", "/api/auth/csrf", null);
    }

    @AfterEach
    void cleanup() throws Exception {
        for (var id : jdbc.queryForList(
                "SELECT application_id FROM merchant_applications WHERE email LIKE 'smtp-recovery%@example.com'",
                Long.class)) {
            jdbc.update("DELETE FROM application_mail_deliveries WHERE application_id=?", id);
            jdbc.update("DELETE FROM application_tokens WHERE application_id=?", id);
            jdbc.update("DELETE FROM merchant_applications WHERE application_id=?", id);
        }
        jdbc.update(
                "DELETE FROM admin_change_histories WHERE actor_user_id IN (SELECT user_id FROM platform_users WHERE login_id LIKE 'smtp_recovery_%')");
        jdbc.update(
                "DELETE FROM user_permissions WHERE user_id IN (SELECT user_id FROM platform_users WHERE login_id LIKE 'smtp_recovery_%')");
        jdbc.update(
                "DELETE FROM login_histories WHERE user_id IN (SELECT user_id FROM platform_users WHERE login_id LIKE 'smtp_recovery_%')");
        jdbc.update("DELETE FROM platform_users WHERE login_id LIKE 'smtp_recovery_%'");
        jdbc.update("DELETE FROM merchants WHERE merchant_code='SMTPREC001'");
        client.send(
                HttpRequest.newBuilder(URI.create(mailBase() + "/api/v1/messages"))
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
    }
    // 실제 HTTP 복구부터 SMTP 수신·새 링크 사용까지 세 종류를 연결한다.
    @Test
    void recovery_whenUsingRealSmtp_deliversNewLinksAndRejectionReason() throws Exception {
        long id = submit("smtp-recovery@example.com", "3333333333", "smtp_recovery_owner");
        String oldVerify = token(text("이메일"));
        allow(id);
        assertThat(resend(id).statusCode()).isEqualTo(200);
        String newVerify = token(text("이메일"));
        assertThat(newVerify).isNotEqualTo(oldVerify);
        assertThat(request(
                                "POST",
                                "/api/merchant-applications/email-verifications",
                                mapper.writeValueAsString(Map.of("token", oldVerify)))
                        .body())
                .contains("APPLICATION_007");
        assertThat(request(
                                "POST",
                                "/api/merchant-applications/email-verifications",
                                mapper.writeValueAsString(Map.of("token", newVerify)))
                        .statusCode())
                .isEqualTo(200);
        assertThat(request(
                                "POST",
                                "/api/admin/merchant-applications/" + id + "/approve",
                                "{\"merchantCode\":\"SMTPREC001\",\"pgFeeRate\":0.01,\"permissions\":[]}")
                        .statusCode())
                .isEqualTo(200);
        String oldSetup = token(text("이용 승인"));
        allow(id);
        assertThat(resend(id).statusCode()).isEqualTo(200);
        String approval = text("이용 승인"), newSetup = token(approval);
        assertThat(approval).contains("smtp_recovery_owner", "결제 연동은 별도", "72시간");
        assertThat(newSetup).isNotEqualTo(oldSetup);
        assertThat(request(
                                "POST",
                                "/api/merchant-applications/initial-password-setups",
                                mapper.writeValueAsString(Map.of("token", oldSetup, "newPassword", "NewOwner123!")))
                        .body())
                .contains("APPLICATION_014");
        assertThat(request(
                                "POST",
                                "/api/merchant-applications/initial-password-setups",
                                mapper.writeValueAsString(Map.of("token", newSetup, "newPassword", "NewOwner123!")))
                        .statusCode())
                .isEqualTo(204);
        long rejected = submit("smtp-recovery-rejected@example.com", "2222222222", "smtp_recovery_rejected");
        applications.verify(new VerifyRequest(token(text("이메일"))));
        assertThat(request(
                                "POST",
                                "/api/admin/merchant-applications/" + rejected + "/reject",
                                "{\"reason\":\"번호 재확인\"}")
                        .statusCode())
                .isEqualTo(200);
        allow(rejected);
        assertThat(resend(rejected).statusCode()).isEqualTo(200);
        assertThat(text("검토 결과")).contains("번호 재확인", "새로 신청");
    }
}
