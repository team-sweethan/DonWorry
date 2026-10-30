package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.dto.MerchantApplicationRequests.*;
import com.donworry.identity.entity.*;
import com.donworry.identity.repository.*;
import com.donworry.identity.service.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "security.csrf.enabled=false")
@Import(MariaDbTestContainerConfig.class)
class MerchantApplicationHttpTests {
    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MerchantApplicationRepository applications;

    @Autowired
    ApplicationTokenRepository tokens;

    @Autowired
    ApplicationMailDeliveryRepository deliveries;

    @Autowired
    MerchantApplicationService service;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    ApplicationPersistenceService persistence;

    @Autowired
    ApplicationRateLimitService limits;

    @Autowired
    org.springframework.security.crypto.password.PasswordEncoder encoder;

    @MockitoBean
    ApplicationMailSender mail;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean(name = "identityClock")
    Clock clock;

    private Instant instant;
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private CookieManager cookies;
    private HttpClient client;
    private static final String PATH = "/api/merchant-applications";

    @BeforeEach
    void setup() throws Exception {
        jdbc.update("DELETE FROM application_mail_deliveries");
        jdbc.update("DELETE FROM application_tokens");
        jdbc.update("DELETE FROM merchant_applications");
        jdbc.update("DELETE FROM application_rate_limits");
        instant = Instant.parse("2026-10-29T05:00:00Z");
        setTime(instant);
        sent.clear();
        doAnswer(invocation -> {
                    assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                                    .isActualTransactionActive())
                            .isFalse();
                    sent.add(invocation.getArgument(1));
                    return null;
                })
                .when(mail)
                .sendVerification(anyString(), anyString());
        cookies = new CookieManager();
        client = HttpClient.newBuilder().cookieHandler(cookies).build();
        assertThat(send("GET", "/api/auth/csrf", null, false).statusCode()).isEqualTo(204);
    }

    private void setTime(Instant time) {
        when(clock.instant()).thenReturn(time);
        when(clock.millis()).thenReturn(time.toEpochMilli());
    }

    private HttpResponse<String> send(String method, String path, String body, boolean csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (csrf)
            request.header(
                    "X-XSRF-TOKEN",
                    cookies.getCookieStore().getCookies().stream()
                            .filter(c -> c.getName().equals("XSRF-TOKEN"))
                            .findFirst()
                            .orElseThrow()
                            .getValue());
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String body(String email, String business) {
        return """
   {"shopUrl":" https://shop.example.com/Products?item=ABC ","merchantName":" 가맹점 ","businessRegistrationNumber":"%s","contactName":" 담당자 ","email":"%s","phone":"010-1234-5678","inquiry":" 문의 ","requestedLoginId":"owner_%s","consent":true,"consentVersion":"2026-10-30.1"}
   """.formatted(business, email, business.replaceAll("[- ]", ""));
    }

    private String raw(int index) {
        return sent.get(index).split("#token=")[1];
    }

    private String verifyBody(String token) {
        return "{\"token\":\"" + token + "\"}";
    }

    private SubmitRequest input(String email, String business) {
        return input(email, business, "owner_" + business);
    }

    private SubmitRequest input(String email, String business, String loginId) {
        return SubmitRequest.builder()
                .shopUrl("https://shop.example.com/Products?item=ABC")
                .merchantName("가맹점")
                .businessRegistrationNumber(business)
                .contactName("담당자")
                .email(email)
                .phone("01012345678")
                .requestedLoginId(loginId)
                .consent(true)
                .consentVersion("2026-10-30.1")
                .build();
    }

    private jakarta.servlet.http.HttpServletRequest request(String ip) {
        var r = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(r.getRemoteAddr()).thenReturn(ip);
        return r;
    }

    private void assertCode(Runnable action, ApplicationErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    // 전역 CSRF 끔에서도 공개 세 POST는 쿠키·헤더를 요구하고 GET은 토큰을 소비하지 않는다.
    @Test
    void publicPosts_whenCsrfDisabledGlobally_stillRequireCsrf() throws Exception {
        for (String path : List.of(PATH, PATH + "/email-verifications", PATH + "/email-verification-resends")) {
            var response = send("POST", path, "{}", false);
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).contains("CSRF_001");
            assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        }
        assertThat(applications.count()).isZero();
        verifyNoInteractions(mail);
        assertThat(send("GET", PATH + "/email-verifications", null, false).statusCode())
                .isEqualTo(405);
    }
    // 실제 저장·정규화·해시 보관·확인 상태 전이와 일회 사용을 검증한다.
    @Test
    void submit_whenValid_persistsAndVerifiesOnce() throws Exception {
        var response = send("POST", PATH, body(" OWNER@Example.com ", "123-45-67890"), true);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).isEqualTo("{\"mailStatus\":\"SENT\"}");
        var app = applications.findAll().getFirst();
        assertThat(app.getEmail()).isEqualTo("owner@example.com");
        assertThat(app.getMerchantName()).isEqualTo("가맹점");
        assertThat(app.getShopUrl()).isEqualTo("https://shop.example.com/Products?item=ABC");
        assertThat(app.getConsentVersion()).isEqualTo("2026-10-30.1");
        assertThat(app.getBusinessRegistrationNumber()).isEqualTo("1234567890");
        assertThat(app.getPhone()).isEqualTo("01012345678");
        assertThat(app.getConsentedAt()).isEqualTo(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
        var token = tokens.findAll().getFirst();
        assertThat(token.getTokenHash())
                .isEqualTo(ApplicationTokenCodec.hash(raw(0)))
                .doesNotContain(raw(0));
        assertThat(token.getExpiresAt()).isEqualTo(token.getCreatedAt().plusHours(24));
        assertThat(send("POST", PATH + "/email-verifications", verifyBody(raw(0)), true)
                        .statusCode())
                .isEqualTo(200);
        assertThat(applications.findById(app.getApplicationId()).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.REVIEW_PENDING);
        var reused = send("POST", PATH + "/email-verifications", verifyBody(raw(0)), true);
        assertThat(reused.statusCode()).isEqualTo(409);
        assertThat(reused.body()).contains("APPLICATION_006");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM merchants WHERE business_registration_number IS NOT NULL", Integer.class))
                .isZero();
    }
    // 한글 도메인은 실제 신청 POST에서도 통과하고 표시용 원문을 그대로 보관한다.
    @Test
    void submit_whenInternationalDomain_persistsOriginalUrl() throws Exception {
        String url = "https://쇼핑몰.한국:8443/Products?item=ABC";
        var response = send(
                "POST",
                PATH,
                body("idn@example.com", "3540000002")
                        .replace(" https://shop.example.com/Products?item=ABC ", " " + url + " "),
                true);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(applications.findAll().getFirst().getShopUrl()).isEqualTo(url);
        verify(mail).sendVerification(eq("idn@example.com"), anyString());
    }

    // 최신 IDN 문자는 브라우저와 같이 접수하고 원문을 보존한다.
    @Test
    void submit_whenModernIdn_persistsOriginalUrl() throws Exception {
        String url = "https://ẞ.de:8443/Products?item=ABC";
        var response = send(
                "POST",
                PATH,
                body("modern-idn@example.com", "3540000003")
                        .replace(" https://shop.example.com/Products?item=ABC ", url),
                true);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(applications.findAll().getFirst().getShopUrl()).isEqualTo(url);
    }

    // 동의·필수값·오래된 동의 문구·잘못된 번호를 로그인 오류 없이 거절한다.
    @Test
    void submit_whenInvalid_rejectsBeforeSaving() throws Exception {
        for (String b : List.of(
                body("bad-email", "1234567890"),
                body("owner@example.com", "123"),
                body("owner@example.com", "1234567890").replace("true", "false"),
                body("owner@example.com", "1234567890").replace("2026-10-30", "2020-01-01"),
                "{}")) {
            var r = send("POST", PATH, b, true);
            assertThat(r.statusCode()).isEqualTo(400);
            assertThat(r.body()).contains("APPLICATION_001").doesNotContain("아이디와 비밀번호");
        }
        assertThat(applications.count()).isZero();
        verifyNoInteractions(mail);
    }
    // 주소 누락·위험 스킴·상대 주소·사용자 정보·길이 초과는 메일 발송과 저장 전에 거절한다.
    @Test
    void submit_whenShopUrlInvalid_rejectsBeforeSaving() throws Exception {
        String valid = " https://shop.example.com/Products?item=ABC ";
        String body = body("owner@example.com", "1234567890");
        for (String value : List.of(
                "",
                "   ",
                "/shop",
                "shop.example.com",
                "javascript:alert(1)",
                "ftp://shop.example.com",
                "https://user:pass@shop.example.com",
                "https://exa_mple.com",
                "https://-shop.example.com",
                "https://user:pass@쇼핑몰.한국",
                "https://@쇼핑몰.한국",
                "https:///shop",
                "https://shop.example.com/" + "a".repeat(2048))) {
            var response = send("POST", PATH, body.replace(valid, value), true);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("APPLICATION_001");
        }
        var missing = send("POST", PATH, body.replace("\"shopUrl\":\"" + valid + "\",", ""), true);
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(applications.count()).isZero();
        verifyNoInteractions(mail);
    }

    // 동시 신청은 같은 이메일·사업자번호 중 하나라도 중복되면 한 건만 만든다.
    @Test
    void submit_whenConcurrentOrDuplicate_createsOnlyOne() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 2; i++) {
                final int n = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        service.submit(input("same@example.com", "123456789" + n), request("127.0.0." + (n + 1)));
                        return "created";
                    } catch (ApplicationException e) {
                        return e.getErrorCode().name();
                    }
                }));
            }
            ready.await();
            go.countDown();
            assertThat(futures.stream().map(f -> {
                        try {
                            return f.get(20, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }))
                    .containsExactlyInAnyOrder("created", "DUPLICATE");
        }
        var app = applications.findAll().getFirst();
        assertCode(
                () -> service.submit(
                        input("other@example.com", app.getBusinessRegistrationNumber()), request("127.0.0.3")),
                ApplicationErrorCode.DUPLICATE);
        assertThat(applications.count()).isEqualTo(1);
        assertThat(deliveries.count()).isEqualTo(1);
        assertThat(sent).hasSize(1);
    }
    // SMTP 실패와 결과 기록 실패는 접수를 롤백하지 않으며 중복 재제출을 막는다.
    @Test
    void submit_whenMailFails_keepsApplicationAndRecordsFailure() throws Exception {
        doThrow(new org.springframework.mail.MailAuthenticationException("sensitive smtp details"))
                .when(mail)
                .sendVerification(anyString(), anyString());
        var r = send("POST", PATH, body("fail@example.com", "1234567890"), true);
        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(r.body()).contains("FAILED").doesNotContain("sensitive");
        assertThat(applications.count()).isEqualTo(1);
        assertThat(deliveries.findAll().getFirst().getFailureCode()).isEqualTo("SMTP_REJECTED");
        assertThat(send("POST", PATH, body("fail@example.com", "1234567890"), true)
                        .statusCode())
                .isEqualTo(409);
    }
    // 재발송은 기존 링크를 폐기하며 정확한 1분 경계에서 허용한다.
    @Test
    void resend_whenAllowed_revokesPreviousLinkAndKeepsSingleApplication() throws Exception {
        send("POST", PATH, body("owner@example.com", "1234567890"), true);
        String resend = "{\"email\":\"OWNER@example.com\"}";
        // 최초 메일 직후 1분 안에는 없는 주소와 같은 202를 주고 발송만 생략한다.
        assertThat(send("POST", PATH + "/email-verification-resends", resend, true)
                        .statusCode())
                .isEqualTo(202);
        assertThat(sent).hasSize(1);
        setTime(instant.plusSeconds(60));
        assertThat(send("POST", PATH + "/email-verification-resends", resend, true)
                        .statusCode())
                .isEqualTo(202);
        assertThat(sent).hasSize(2);
        assertThat(raw(0)).isNotEqualTo(raw(1));
        var old = send("POST", PATH + "/email-verifications", verifyBody(raw(0)), true);
        assertThat(old.statusCode()).isEqualTo(410);
        assertThat(old.body()).contains("APPLICATION_007");
        assertThat(send("POST", PATH + "/email-verifications", verifyBody(raw(1)), true)
                        .statusCode())
                .isEqualTo(200);
        assertThat(applications.count()).isEqualTo(1);
    }
    // 최초 메일은 1분 쿨다운만 걸고 하루 5회에는 세지 않는다. 재발송만 24시간 5회까지 허용한다.
    @Test
    void resend_afterInitialMail_allowsFiveResendsPerDay() throws Exception {
        send("POST", PATH, body("quota@example.com", "1234567890"), true);
        String resend = "{\"email\":\"quota@example.com\"}";
        for (int i = 1; i <= 5; i++) {
            setTime(instant.plusSeconds(i * 60L));
            assertThat(send("POST", PATH + "/email-verification-resends", resend, true)
                            .statusCode())
                    .isEqualTo(202);
        }
        setTime(instant.plusSeconds(3600));
        assertThat(send("POST", PATH + "/email-verification-resends", resend, true)
                        .statusCode())
                .isEqualTo(429);
        assertThat(sent).hasSize(6);
    }
    // 재발송은 IP당 1시간 10회까지라, 없는 주소를 바꿔 가며 요청해도 제한 행이 무한히 늘지 않는다.
    @Test
    void resend_whenManyEmailsFromOneIp_limitsByIp() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(send(
                                    "POST",
                                    PATH + "/email-verification-resends",
                                    "{\"email\":\"r" + i + "@example.com\"}",
                                    true)
                            .statusCode())
                    .isEqualTo(202);
        }
        assertThat(send("POST", PATH + "/email-verification-resends", "{\"email\":\"r10@example.com\"}", true)
                        .statusCode())
                .isEqualTo(429);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM application_rate_limits WHERE scope <> 'RESEND_IP'", Integer.class))
                .isEqualTo(10);
    }
    // 만료 행이 한 번의 삭제 묶음(1,000행)보다 많아도 정리 한 번에 모두 지운다.
    @Test
    void deleteExpired_whenMoreThanOneBatch_removesAll() {
        jdbc.batchUpdate(
                "INSERT INTO application_rate_limits (scope, subject_hash, attempt_times, expires_at) VALUES ('RESEND_EMAIL', ?, '', ?)",
                java.util.stream.IntStream.range(0, 2500)
                        .mapToObj(i -> new Object[] {String.format("%064d", i), LocalDateTime.of(2026, 10, 28, 0, 0)})
                        .toList());
        limits.deleteExpired();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM application_rate_limits", Integer.class))
                .isZero();
    }
    // 메일 재발송은 없는 주소와 확인 완료 주소를 같은 응답으로 처리한다.
    @Test
    void resend_whenUnknown_returnsGenericResponseAndEnforcesSameLimit() throws Exception {
        var r = send("POST", PATH + "/email-verification-resends", "{\"email\":\"absent@example.com\"}", true);
        assertThat(r.statusCode()).isEqualTo(202);
        assertThat(r.body()).isEmpty();
        verifyNoInteractions(mail);
        assertThat(send("POST", PATH + "/email-verification-resends", "{\"email\":\"absent@example.com\"}", true)
                        .statusCode())
                .isEqualTo(429);
    }
    // 만료 경계·변조·다른 용도 토큰은 확인에 사용할 수 없다.
    @Test
    void verify_whenExpiredTamperedOrWrongPurpose_rejects() throws Exception {
        send("POST", PATH, body("owner@example.com", "1234567890"), true);
        assertCode(() -> persistence.verify("A".repeat(43)), ApplicationErrorCode.INVALID_TOKEN);
        setTime(instant.plusSeconds(86400));
        assertCode(() -> persistence.verify(raw(0)), ApplicationErrorCode.EXPIRED_TOKEN);
        setTime(instant);
        Long userId = jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id='admin'", Long.class);
        jdbc.update("UPDATE application_tokens SET purpose='INITIAL_PASSWORD_SETUP', user_id=?", userId);
        assertCode(() -> persistence.verify(raw(0)), ApplicationErrorCode.INVALID_TOKEN);
        assertThat(applications.findAll().getFirst().getStatus()).isEqualTo(ApplicationStatus.EMAIL_PENDING);
    }
    // 요청 제한은 최근 시간창·재시작을 견디는 DB 기록으로 판정하며 정확한 경계에서 해제한다.
    @Test
    void limits_whenAtBoundary_countAtomicallyAndExpire() throws Exception {
        for (int i = 0; i < 5; i++) limits.submission("192.0.2.1");
        assertCode(() -> limits.submission("192.0.2.1"), ApplicationErrorCode.RATE_LIMITED);
        assertThat(jdbc.queryForObject(
                                "SELECT attempt_times FROM application_rate_limits WHERE scope='SUBMIT_IP'",
                                String.class)
                        .split(","))
                .hasSize(5);
        setTime(instant.plusSeconds(3600));
        limits.submission("192.0.2.1");
        setTime(instant);
        for (int i = 0; i < 5; i++) {
            setTime(instant.plusSeconds(i * 60));
            limits.resend("limit@example.com", "192.0.2.9");
        }
        setTime(instant.plusSeconds(3600));
        assertCode(() -> limits.resend("limit@example.com", "192.0.2.9"), ApplicationErrorCode.RATE_LIMITED);
        setTime(instant.plusSeconds(86400));
        limits.resend("limit@example.com", "192.0.2.9");
        setTime(instant.plusSeconds(172801));
        limits.deleteExpired();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM application_rate_limits", Integer.class))
                .isZero();
    }
    // 확인과 재발송이 경쟁해도 이전 링크 확인과 새 링크 발급을 동시에 허용하지 않는다.
    @Test
    void verify_whenRacingResend_serializesOnApplication() throws Exception {
        service.submit(input("race@example.com", "1234567890"), request("192.0.2.1"));
        String old = raw(0);
        setTime(instant.plusSeconds(60));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var verify = executor.submit(() -> {
                start.await();
                try {
                    persistence.verify(old);
                    return "verified";
                } catch (ApplicationException e) {
                    return e.getErrorCode().name();
                }
            });
            var resend = executor.submit(() -> {
                start.await();
                return persistence.resend("race@example.com");
            });
            start.countDown();
            String result = verify.get(20, TimeUnit.SECONDS);
            var prepared = resend.get(20, TimeUnit.SECONDS);
            if (prepared.isPresent()) {
                assertThat(result).isEqualTo("REVOKED_TOKEN");
                persistence.verify(prepared.get().token());
            } else assertThat(result).isEqualTo("verified");
        }
        assertThat(applications.findAll().getFirst().getStatus()).isEqualTo(ApplicationStatus.REVIEW_PENDING);
    }
    // 강제 비밀번호 변경 세션도 공개 확인을 이용할 수 있고 기존 업무 차단은 유지된다.
    @Test
    void publicVerification_whenForcedPasswordSession_doesNotRedirectToPasswordChange() throws Exception {
        String id = "application_forced";
        jdbc.update(
                "INSERT INTO platform_users(login_id,password_hash,display_name,status,role,created_at,password_change_required) VALUES(?,?,?,'ACTIVE','SECURITY_STAFF',UTC_TIMESTAMP(6),TRUE)",
                id,
                encoder.encode("Application123"),
                "테스트");
        try {
            assertThat(send(
                                    "POST",
                                    "/api/auth/login",
                                    "{\"loginId\":\"" + id + "\",\"password\":\"Application123\"}",
                                    true)
                            .statusCode())
                    .isEqualTo(200);
            send("GET", "/api/auth/csrf", null, false);
            var r = send("POST", PATH + "/email-verifications", verifyBody("A".repeat(43)), true);
            assertThat(r.statusCode()).isEqualTo(400);
            assertThat(r.body()).contains("APPLICATION_004");
            assertThat(send("GET", "/api/admin/users", null, false).body()).contains("AUTH_008");
        } finally {
            Long userId = jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id=?", Long.class, id);
            jdbc.update("DELETE FROM login_histories WHERE user_id=?", userId);
            jdbc.update("DELETE FROM platform_users WHERE user_id=?", userId);
        }
    }
    // 메일 결과 저장 실패는 이미 커밋된 접수를 유지하고 성공 발송으로 단정하지 않는다.
    @Test
    void submit_whenDeliveryResultCannotBeSaved_returnsUnknownAndKeepsCommittedApplication() throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test"))
                .when(persistence)
                .complete(anyLong(), any(), any());
        var response = send("POST", PATH, body("unknown@example.com", "1234567890"), true);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("UNKNOWN");
        assertThat(applications.count()).isEqualTo(1);
        assertThat(deliveries.findAll().getFirst().getStatus()).isEqualTo(ApplicationMailStatus.PENDING);
        assertThat(sent).hasSize(1);
    }
    // 사용 중인 아이디는 저장 전에 거절하고 필수·형식 오류를 구분한다.
    @Test
    void submit_whenRequestedLoginUnavailableOrInvalid_rejectsWithoutSaving() throws Exception {
        var occupied =
                send("POST", PATH, body("owner@example.com", "1234567890").replace("owner_1234567890", "admin"), true);
        assertThat(occupied.statusCode()).isEqualTo(409);
        assertThat(occupied.body()).contains("APPLICATION_018");
        for (String login : List.of("", "AOwner", "ab", "has-dash")) {
            var invalid = send(
                    "POST", PATH, body("owner@example.com", "1234567890").replace("owner_1234567890", login), true);
            assertThat(invalid.statusCode()).isEqualTo(400);
            assertThat(invalid.body()).contains("APPLICATION_001");
        }
        assertThat(applications.count()).isZero();
        verifyNoInteractions(mail);
    }
    // 서로 다른 이메일·사업자번호로 같은 아이디를 동시에 신청해도 한 건만 예약한다.
    @Test
    void submit_whenRequestedLoginRaces_reservesOnlyOnce() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var go = new CountDownLatch(1);
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 2; i++) {
                final int n = i;
                futures.add(executor.submit(() -> {
                    go.await();
                    try {
                        service.submit(
                                input("claim" + n + "@example.com", "123456789" + n, "shared_owner"),
                                request("192.0.2." + (n + 10)));
                        return "created";
                    } catch (ApplicationException e) {
                        return e.getErrorCode().name();
                    }
                }));
            }
            go.countDown();
            assertThat(List.of(
                            futures.get(0).get(20, TimeUnit.SECONDS),
                            futures.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("created", "LOGIN_ID_UNAVAILABLE");
        }
        assertThat(applications.count()).isEqualTo(1);
        assertThat(sent).hasSize(1);
        assertThat(applications.findAll().getFirst().getRequestedLoginId()).isEqualTo("shared_owner");
    }
    // 동일 IP의 동시 제한 판정은 최대 5건만 허용한다.
    @Test
    void limits_whenConcurrent_allowsAtMostFive() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++)
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        limits.submission("192.0.2.50");
                        return true;
                    } catch (ApplicationException exception) {
                        assertThat(exception.getErrorCode()).isEqualTo(ApplicationErrorCode.RATE_LIMITED);
                        return false;
                    }
                }));
            start.countDown();
            int accepted = 0;
            for (var result : results) if (result.get(20, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(5);
        }
    }
}
