package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.entity.*;
import com.donworry.identity.repository.*;
import com.donworry.identity.service.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.*;
import tools.jackson.databind.json.JsonMapper;

@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "security.csrf.enabled=false")
@Import(MariaDbTestContainerConfig.class)
class ApplicationApprovalHttpTests {
    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MerchantApplicationRepository applications;

    @MockitoSpyBean
    ApplicationTokenRepository tokens;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JsonMapper json;

    @Autowired
    MerchantApplicationService applicationService;

    @MockitoBean
    ApplicationMailSender mail;

    @MockitoSpyBean
    AdminChangeHistoryService histories;

    @MockitoSpyBean
    ApplicationPersistenceService persistence;

    private Browser admin;
    private final AtomicReference<String> link = new AtomicReference<>();
    private static final String BASE = "/api/admin/merchant-applications";
    private static final String SETUP = "/api/merchant-applications/initial-password-setups";

    @BeforeEach
    void prepare() throws Exception {
        cleanup();
        createUser("approval_admin", "ADMIN", false);
        createUser("approval_admin2", "ADMIN", false);
        createUser("approval_staff", "SECURITY_STAFF", false);
        admin = new Browser();
        admin.login("approval_admin");
        link.set(null);
        doAnswer(i -> {
                    assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                                    .isActualTransactionActive())
                            .isFalse();
                    link.set(i.getArgument(2));
                    assertThat(jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM merchant_applications WHERE status='APPROVED'",
                                    Integer.class))
                            .isPositive();
                    return null;
                })
                .when(mail)
                .sendApproval(anyString(), anyString(), anyString());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM application_mail_deliveries");
        jdbc.update("DELETE FROM application_tokens");
        jdbc.update("DELETE FROM merchant_applications");
        jdbc.update(
                "DELETE FROM admin_change_histories WHERE target_user_id IN (SELECT user_id FROM platform_users WHERE login_id LIKE 'approval_%') OR actor_user_id IN (SELECT user_id FROM platform_users WHERE login_id LIKE 'approval_%') OR target_merchant_id IN (SELECT merchant_id FROM merchants WHERE merchant_code LIKE 'APPR%')");
        jdbc.update(
                "DELETE FROM login_histories WHERE user_id IN (SELECT user_id FROM platform_users WHERE login_id LIKE 'approval_%')");
        jdbc.update(
                "DELETE FROM user_permissions WHERE user_id IN (SELECT user_id FROM platform_users WHERE login_id LIKE 'approval_%')");
        jdbc.update("DELETE FROM platform_users WHERE login_id LIKE 'approval_%'");
        jdbc.update("DELETE FROM merchants WHERE merchant_code LIKE 'APPR%'");
    }

    void createUser(String login, String role, boolean forced) {
        jdbc.update(
                "INSERT INTO platform_users(login_id,password_hash,display_name,status,role,created_at,password_change_required) VALUES(?,?,?,'ACTIVE',?,UTC_TIMESTAMP(6),?)",
                login,
                encoder.encode("Approval123!"),
                login,
                role,
                forced);
    }

    Long application(boolean verified) {
        return application(verified, "approval_owner");
    }

    Long application(boolean verified, String requestedLoginId) {
        var app = applications.saveAndFlush(MerchantApplication.submit(
                "달콤한 가게",
                "1234567890",
                "신청자",
                "approval@example.com",
                "01012345678",
                requestedLoginId,
                "가맹점 문의",
                "2026-10-29",
                LocalDateTime.now(ZoneOffset.UTC)));
        if (verified)
            jdbc.update(
                    "UPDATE merchant_applications SET status='REVIEW_PENDING',email_verified_at=UTC_TIMESTAMP(6) WHERE application_id=?",
                    app.getApplicationId());
        return app.getApplicationId();
    }

    String approval(String code, String login) {
        return "{\"merchantCode\":\"" + code + "\",\"pgFeeRate\":\"0.010000\",\"loginId\":\"" + login
                + "\",\"permissions\":[\"FDS_VIEW\",\"FINANCE_VIEW\"]}";
    }

    HttpResponse<String> approve(Long id) throws Exception {
        return admin.send("POST", BASE + "/" + id + "/approve", approval("APPR001", "approval_owner"), true);
    }

    String raw() {
        return link.get().split("#token=")[1];
    }

    String setupBody(String token, String password) {
        return json.writeValueAsString(Map.of("token", token, "newPassword", password));
    }

    MerchantApplication saved(Long id) {
        return applications.findById(id).orElseThrow();
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
    // 승인은 발급 정보를 함께 커밋하고 비밀번호 원문을 응답하지 않는다.
    @Test
    void approve_whenVerified_createsMerchantUserPermissionsAndTokenAtomically() throws Exception {
        Long id = application(true);
        var result = approve(id);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).contains("APPROVED", "SENT").doesNotContain("password");
        assertThat(result.headers().firstValue("cache-control")).contains("no-store");
        var a = saved(id);
        assertThat(a.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(a.getReviewerUserId()).isNotNull();
        assertThat(a.getReviewedAt()).isNotNull();
        assertThat(jdbc.queryForMap(
                        "SELECT business_registration_number,pg_fee_rate,secret_reference FROM merchants WHERE merchant_id=?",
                        a.getMerchantId()))
                .containsEntry("business_registration_number", "1234567890")
                .containsEntry("secret_reference", null);
        assertThat(jdbc.queryForList(
                        "SELECT permission_code FROM user_permissions WHERE user_id=? ORDER BY permission_code",
                        String.class,
                        a.getUserId()))
                .containsExactly("FDS_VIEW", "FINANCE_VIEW");
        assertThat(jdbc.queryForObject("SELECT role FROM platform_users WHERE user_id=?", String.class, a.getUserId()))
                .isEqualTo("OPERATOR");
        assertThat(jdbc.queryForObject(
                        "SELECT password_change_required FROM platform_users WHERE user_id=?",
                        Boolean.class,
                        a.getUserId()))
                .isTrue();
        var token = tokens.findAll().getFirst();
        assertThat(token.getTokenHash()).isEqualTo(ApplicationTokenCodec.hash(raw()));
        assertThat(token.getUserId()).isEqualTo(a.getUserId());
        assertThat(token.getExpiresAt()).isEqualTo(token.getCreatedAt().plusHours(72));
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM admin_change_histories WHERE target_user_id=?",
                        Integer.class,
                        a.getUserId()))
                .isEqualTo(2);
        verify(mail).sendApproval(eq("approval@example.com"), eq("approval_owner"), contains("/apply/password#token="));
    }
    // 이메일 미확인 신청은 승인·반려할 수 없다.
    @Test
    void review_whenEmailPending_rejectsBothActions() throws Exception {
        Long id = application(false);
        assertThat(approve(id).statusCode()).isEqualTo(409);
        assertThat(admin.send("POST", BASE + "/" + id + "/reject", "{\"reason\":\"確認してください\"}", true)
                        .statusCode())
                .isEqualTo(409);
        assertThat(saved(id).getMerchantId()).isNull();
        verifyNoInteractions(mail);
    }
    // 중복 승인은 계정 발급과 메일 발송을 반복하지 않는다.
    @Test
    void approve_whenRepeated_returnsConflictWithoutDuplicating() throws Exception {
        Long id = application(true);
        assertThat(approve(id).statusCode()).isEqualTo(200);
        assertThat(approve(id).statusCode()).isEqualTo(409);
        assertThat(count("application_tokens")).isEqualTo(1);
        assertThat(count("application_mail_deliveries")).isEqualTo(1);
        verify(mail, times(1)).sendApproval(anyString(), anyString(), anyString());
    }
    // 다른 관리자가 서로 다른 정보로 동시에 승인해도 한 번만 발급한다.
    @Test
    void approve_whenConcurrentAcrossAdmins_commitsOnlyOne() throws Exception {
        Long id = application(true);
        var other = new Browser();
        other.login("approval_admin2");
        var statuses = concurrent(
                () -> approve(id).statusCode(),
                () -> other.send("POST", BASE + "/" + id + "/approve", approval("APPR002", "approval_owner2"), true)
                        .statusCode());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(count("application_tokens")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM merchants WHERE merchant_code LIKE 'APPR%'", Integer.class))
                .isEqualTo(1);
    }
    // 동시 승인·반려는 둘 중 하나만 반영한다.
    @Test
    void review_whenApproveRacesReject_commitsOneOutcome() throws Exception {
        Long id = application(true);
        var other = new Browser();
        other.login("approval_admin2");
        assertThat(concurrent(
                        () -> approve(id).statusCode(),
                        () -> other.send("POST", BASE + "/" + id + "/reject", "{\"reason\":\"정보 확인 필요\"}", true)
                                .statusCode()))
                .containsExactlyInAnyOrder(200, 409);
        var a = saved(id);
        assertThat(count("application_mail_deliveries")).isEqualTo(1);
        assertThat(a.getMerchantId() != null).isEqualTo(a.getStatus() == ApplicationStatus.APPROVED);
    }
    // 계정 아이디 충돌은 앞서 생성한 가맹점까지 롤백한다.
    @Test
    void approve_whenLoginConflicts_rollsBackMerchantAndApplication() throws Exception {
        Long id = application(true);
        createUser("approval_owner", "SECURITY_STAFF", false);
        assertThat(approve(id).statusCode()).isEqualTo(409);
        assertThat(saved(id).getStatus()).isEqualTo(ApplicationStatus.REVIEW_PENDING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchants WHERE merchant_code='APPR001'", Integer.class))
                .isZero();
        assertThat(count("application_tokens")).isZero();
        verifyNoInteractions(mail);
    }
    // 이력 저장 실패는 가맹점·계정·권한·토큰·승인을 전부 롤백한다.
    @Test
    void approve_whenPermissionHistoryFails_rollsBackEverything() throws Exception {
        Long id = application(true);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test"))
                .when(org.springframework.test.util.AopTestUtils.<AdminChangeHistoryService>getUltimateTargetObject(
                        histories))
                .recordUser(anyLong(), anyLong(), eq("PERMISSIONS_CHANGED"), any(), any());
        assertThat(approve(id).statusCode()).isEqualTo(503);
        assertThat(saved(id).getStatus()).isEqualTo(ApplicationStatus.REVIEW_PENDING);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM platform_users WHERE login_id='approval_owner'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchants WHERE merchant_code='APPR001'", Integer.class))
                .isZero();
        assertThat(count("application_tokens")).isZero();
        verifyNoInteractions(mail);
    }
    // 승인 메일 오류에도 발급은 유지하고 재승인하지 않는다.
    @Test
    void approve_whenSmtpFails_keepsIssuedAccountAndFailedDelivery() throws Exception {
        doThrow(new org.springframework.mail.MailAuthenticationException("private smtp info"))
                .when(mail)
                .sendApproval(anyString(), anyString(), anyString());
        Long id = application(true);
        var r = approve(id);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("APPROVED", "FAILED").doesNotContain("private");
        assertThat(saved(id).getUserId()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM application_mail_deliveries", String.class))
                .isEqualTo("FAILED");
        assertThat(approve(id).statusCode()).isEqualTo(409);
    }
    // 발송 결과 저장 오류를 발급 실패나 발송 성공으로 표현하지 않는다.
    @Test
    void approve_whenMailResultSaveFails_returnsUnknownAndKeepsApproval() throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test"))
                .when(persistence)
                .complete(anyLong(), any(), any());
        Long id = application(true);
        assertThat(approve(id).body()).contains("UNKNOWN");
        assertThat(saved(id).getStatus()).isEqualTo(ApplicationStatus.APPROVED);
    }
    // 반려 사유를 정리해 저장하고 메일을 보내며 계정은 생성하지 않는다.
    @Test
    void reject_whenValid_recordsReasonAndSendsAfterCommit() throws Exception {
        Long id = application(true);
        doAnswer(i -> {
                    assertThat(saved(id).getStatus()).isEqualTo(ApplicationStatus.REJECTED);
                    assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                                    .isActualTransactionActive())
                            .isFalse();
                    return null;
                })
                .when(mail)
                .sendRejection(anyString(), anyString());
        var r = admin.send("POST", BASE + "/" + id + "/reject", "{\"reason\":\"  번호를 확인해 주세요.  \"}", true);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(saved(id).getRejectionReason()).isEqualTo("번호를 확인해 주세요.");
        assertThat(saved(id).getUserId()).isNull();
        assertThat(count("application_tokens")).isZero();
        verify(mail).sendRejection("approval@example.com", "번호를 확인해 주세요.");
        assertThat(application(false)).isNotEqualTo(id);
    }
    // 첫 설정은 CSRF를 요구하며 설정 후 로그인과 업무 진입이 가능하다.
    @Test
    void setup_whenValid_consumesTokenAndAllowsLogin() throws Exception {
        Long id = application(true);
        approve(id);
        var visitor = new Browser();
        var noCsrf = visitor.send("POST", SETUP, setupBody(raw(), "OwnerPass123!"), false);
        assertThat(noCsrf.statusCode()).isEqualTo(403);
        assertThat(noCsrf.body()).contains("CSRF_001");
        assertThat(visitor.send("POST", SETUP, setupBody(raw(), "OwnerPass123!"), true)
                        .statusCode())
                .isEqualTo(204);
        assertThat(tokens.findAll().getFirst().getUsedAt()).isNotNull();
        assertThat(jdbc.queryForObject(
                        "SELECT password_change_required FROM platform_users WHERE user_id=?",
                        Boolean.class,
                        saved(id).getUserId()))
                .isFalse();
        visitor.login("approval_owner", "OwnerPass123!");
        assertThat(visitor.send("GET", "/api/auth/me", null, false).body())
                .contains("\"passwordChangeRequired\":false");
        assertThat(visitor.send("POST", SETUP, setupBody(raw(), "Changed123!"), true)
                        .body())
                .contains("APPLICATION_013");
    }
    // 잘못된 비밀번호는 토큰을 소비하지 않고 수정 후 다시 설정할 수 있다.
    @Test
    void setup_whenPasswordInvalid_doesNotConsumeToken() throws Exception {
        approve(application(true));
        var visitor = new Browser();
        for (String password : List.of("short", "approval_owner", "가".repeat(25) + "A1")) {
            var r = visitor.send("POST", SETUP, setupBody(raw(), password), true);
            assertThat(r.statusCode()).isEqualTo(400);
            assertThat(r.body()).contains("AUTH_006");
            assertThat(tokens.findAll().getFirst().getUsedAt()).isNull();
        }
        assertThat(visitor.send("POST", SETUP, setupBody(raw(), "OwnerPass123!"), true)
                        .statusCode())
                .isEqualTo(204);
    }
    // 만료·폐기·변조·이메일 확인 용도 토큰을 구분해 거절한다.
    @Test
    void setup_whenExpiredRevokedTamperedOrWrongPurpose_rejects() throws Exception {
        approve(application(true));
        var visitor = new Browser();
        assertThat(visitor.send("POST", SETUP, setupBody("A".repeat(43), "OwnerPass123!"), true)
                        .body())
                .contains("APPLICATION_011");
        jdbc.update(
                "UPDATE application_tokens SET created_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 73 HOUR),expires_at=UTC_TIMESTAMP(6)");
        assertThat(visitor.send("POST", SETUP, setupBody(raw(), "OwnerPass123!"), true)
                        .body())
                .contains("APPLICATION_012");
        jdbc.update(
                "UPDATE application_tokens SET expires_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),revoked_at=UTC_TIMESTAMP(6)");
        assertThat(visitor.send("POST", SETUP, setupBody(raw(), "OwnerPass123!"), true)
                        .body())
                .contains("APPLICATION_014");
        jdbc.update("UPDATE application_tokens SET purpose='EMAIL_VERIFICATION',user_id=NULL,revoked_at=NULL");
        assertThat(visitor.send("POST", SETUP, setupBody(raw(), "OwnerPass123!"), true)
                        .body())
                .contains("APPLICATION_011");
    }
    // 비밀번호 초기화가 기존 첫 설정 링크를 같은 커밋에서 폐기한다.
    @Test
    void reset_whenSetupPending_revokesLinkAndPreservesForcedChange() throws Exception {
        Long id = application(true);
        approve(id);
        Long userId = saved(id).getUserId();
        var r = admin.send("POST", "/api/admin/users/" + userId + "/password-reset", "{}", true);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(tokens.findAll().getFirst().getRevokedAt()).isNotNull();
        var visitor = new Browser();
        assertThat(visitor.send("POST", SETUP, setupBody(raw(), "OwnerPass123!"), true)
                        .body())
                .contains("APPLICATION_014");
        visitor.login(
                "approval_owner",
                json.readTree(r.body()).get("temporaryPassword").asText());
        assertThat(visitor.send("GET", "/api/admin/users", null, false).body()).contains("AUTH_008");
    }
    // 첫 설정 두 요청은 하나만 비밀번호를 바꾸고 다른 요청은 사용됨으로 끝난다.
    @Test
    void setup_whenConcurrent_consumesOnce() throws Exception {
        approve(application(true));
        var one = new Browser();
        var two = new Browser();
        String token = raw();
        assertThat(concurrent(
                        () -> one.send("POST", SETUP, setupBody(token, "FirstPass123!"), true)
                                .statusCode(),
                        () -> two.send("POST", SETUP, setupBody(token, "SecondPass123!"), true)
                                .statusCode()))
                .containsExactlyInAnyOrder(204, 409);
    }
    // 초기화와 첫 설정이 겹쳐도 최종 비밀번호는 초기화 결과이고 기존 링크는 쓸 수 없다.
    @Test
    void setup_whenRacingAdminReset_neverOverridesCommittedReset() throws Exception {
        Long id = application(true);
        approve(id);
        Long userId = saved(id).getUserId();
        String token = raw();
        var visitor = new Browser();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var go = new CountDownLatch(1);
            var setup = executor.submit(() -> {
                go.await();
                return visitor.send("POST", SETUP, setupBody(token, "OwnerPass123!"), true);
            });
            var reset = executor.submit(() -> {
                go.await();
                return admin.send("POST", "/api/admin/users/" + userId + "/password-reset", "{}", true);
            });
            go.countDown();
            assertThat(setup.get(20, TimeUnit.SECONDS).statusCode()).isIn(204, 410);
            var response = reset.get(20, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            String password =
                    json.readTree(response.body()).get("temporaryPassword").asText();
            assertThat(encoder.matches(
                            password,
                            jdbc.queryForObject(
                                    "SELECT password_hash FROM platform_users WHERE user_id=?", String.class, userId)))
                    .isTrue();
            assertThat(jdbc.queryForObject(
                            "SELECT password_change_required FROM platform_users WHERE user_id=?",
                            Boolean.class,
                            userId))
                    .isTrue();
        }
    }
    // 정지된 계정과 가맹점에서는 링크를 사용하지 않고 정상화 후 설정할 수 있다.
    @Test
    void setup_whenAccountOrMerchantInactive_blocksWithoutConsuming() throws Exception {
        Long id = application(true);
        approve(id);
        var a = saved(id);
        var visitor = new Browser();
        jdbc.update("UPDATE platform_users SET status='DISABLED' WHERE user_id=?", a.getUserId());
        assertThat(visitor.send("POST", SETUP, setupBody(raw(), "OwnerPass123!"), true)
                        .body())
                .contains("APPLICATION_015");
        jdbc.update("UPDATE platform_users SET status='ACTIVE' WHERE user_id=?", a.getUserId());
        jdbc.update("UPDATE merchants SET status='INACTIVE' WHERE merchant_id=?", a.getMerchantId());
        assertThat(visitor.send("POST", SETUP, setupBody(raw(), "OwnerPass123!"), true)
                        .body())
                .contains("APPLICATION_015");
        assertThat(tokens.findAll().getFirst().getUsedAt()).isNull();
    }
    // 목록·상세와 검토 API는 관리자만 사용하며 필터·입력을 검증한다.
    @Test
    void adminEndpoints_whenUnauthorizedOrInvalid_rejectAndFilter() throws Exception {
        Long id = application(true);
        var anonymous = new Browser();
        var staff = new Browser();
        staff.login("approval_staff");
        for (String path : List.of(BASE, BASE + "/" + id)) {
            assertThat(anonymous.send("GET", path, null, false).statusCode()).isEqualTo(401);
            assertThat(staff.send("GET", path, null, false).statusCode()).isEqualTo(403);
        }
        assertThat(staff.send("POST", BASE + "/" + id + "/approve", approval("APPR001", "approval_owner"), true)
                        .statusCode())
                .isEqualTo(403);
        assertThat(admin.send(
                                "GET",
                                BASE + "?status=REVIEW_PENDING&keyword="
                                        + URLEncoder.encode("달콤", java.nio.charset.StandardCharsets.UTF_8),
                                null,
                                false)
                        .body())
                .contains("달콤한 가게");
        assertThat(admin.send("GET", BASE + "?status=REJECTED", null, false).body())
                .contains("\"totalElements\":0");
        assertThat(admin.send("GET", BASE + "?keyword=123-45-67890", null, false)
                        .body())
                .contains("달콤한 가게");
        for (String query : List.of("?page=-1", "?size=101", "?status=UNKNOWN", "?page=abc"))
            assertThat(admin.send("GET", BASE + query, null, false).statusCode())
                    .isEqualTo(400);
        assertThat(admin.send("GET", BASE + "/99999999", null, false).statusCode())
                .isEqualTo(404);
        assertThat(admin.send("POST", BASE + "/" + id + "/reject", "{\"reason\":\" \"}", true)
                        .statusCode())
                .isEqualTo(400);
        assertThat(admin.send("POST", BASE + "/" + id + "/approve", approval("bad", "approval_owner"), true)
                        .statusCode())
                .isEqualTo(400);
        verifyNoInteractions(mail);
    }

    // 기존 사업자번호는 경고로 조회하고 새 가맹점 발급을 허용하며 빈 기능 권한도 기존 정책대로 허용한다.
    @Test
    void approve_whenBusinessAlreadyRegistered_warnsButAllowsSeparateMerchant() throws Exception {
        Long first = application(true);
        assertThat(approve(first).statusCode()).isEqualTo(200);
        Long second = application(true, "approval_owner2");
        var detail = admin.send("GET", BASE + "/" + second, null, false);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(json.readTree(detail.body()).get("duplicateMerchants").size())
                .isEqualTo(1);
        String input = approval("APPR002", "approval_owner2").replace("[\"FDS_VIEW\",\"FINANCE_VIEW\"]", "[]");
        assertThat(admin.send("POST", BASE + "/" + second + "/approve", input, true)
                        .statusCode())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM user_permissions WHERE user_id=?",
                        Integer.class,
                        saved(second).getUserId()))
                .isZero();
    }
    // 초기화 이력 실패는 비밀번호 변경과 토큰 폐기를 함께 롤백한다.
    @Test
    void reset_whenHistoryFails_keepsPasswordAndSetupLink() throws Exception {
        Long id = application(true);
        approve(id);
        Long userId = saved(id).getUserId();
        String hash =
                jdbc.queryForObject("SELECT password_hash FROM platform_users WHERE user_id=?", String.class, userId);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test"))
                .when(org.springframework.test.util.AopTestUtils.<AdminChangeHistoryService>getUltimateTargetObject(
                        histories))
                .recordUser(anyLong(), anyLong(), eq("PASSWORD_RESET"), any(), any());
        assertThat(admin.send("POST", "/api/admin/users/" + userId + "/password-reset", "{}", true)
                        .statusCode())
                .isEqualTo(500);
        assertThat(jdbc.queryForObject(
                        "SELECT password_hash FROM platform_users WHERE user_id=?", String.class, userId))
                .isEqualTo(hash);
        assertThat(tokens.findAll().getFirst().getRevokedAt()).isNull();
    }

    // 관리자가 다른 아이디를 보내도 신청자가 선택한 아이디로만 계정을 발급한다.
    @Test
    void approve_whenAdminSuppliesDifferentLogin_usesApplicantSelection() throws Exception {
        Long id = application(true);
        assertThat(admin.send("POST", BASE + "/" + id + "/approve", approval("APPR001", "admin_chosen_id"), true)
                        .statusCode())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "SELECT login_id FROM platform_users WHERE user_id=?",
                        String.class,
                        saved(id).getUserId()))
                .isEqualTo("approval_owner");
        verify(mail).sendApproval(eq("approval@example.com"), eq("approval_owner"), anyString());
    }
    // 진행 중 신청의 아이디는 수동 계정 생성도 가져갈 수 없고 반려 후에는 해제된다.
    @Test
    void createAccount_whenLoginReserved_blocksUntilApplicationRejected() throws Exception {
        Long id = application(true);
        String input = "{\"loginId\":\"approval_owner\",\"displayName\":\"직원\",\"role\":\"SECURITY_STAFF\"}";
        var conflict = admin.send("POST", "/api/admin/users", input, true);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("ADMIN_003");
        assertThat(admin.send("POST", BASE + "/" + id + "/reject", "{\"reason\":\"새 신청 안내\"}", true)
                        .statusCode())
                .isEqualTo(200);
        assertThat(admin.send("POST", "/api/admin/users", input, true).statusCode())
                .isEqualTo(201);
    }
    // 공개 신청과 관리자 수동 생성이 겹쳐도 같은 아이디를 계정·신청에 동시에 부여하지 않는다.
    @Test
    void submit_whenRacingManualAccountCreation_assignsLoginToOneOwner() throws Exception {
        var input = com.donworry.identity.dto.MerchantApplicationRequests.SubmitRequest.builder()
                .merchantName("가맹점")
                .businessRegistrationNumber("3456789012")
                .contactName("담당자")
                .email("claim@example.com")
                .phone("01012345678")
                .requestedLoginId("approval_claim")
                .consent(true)
                .consentVersion("2026-10-29.1")
                .build();
        var request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.0.2.44");
        var statuses = concurrent(
                () -> {
                    try {
                        applicationService.submit(input, request);
                        return 201;
                    } catch (ApplicationException e) {
                        assertThat(e.getErrorCode()).isEqualTo(ApplicationErrorCode.LOGIN_ID_UNAVAILABLE);
                        return 409;
                    }
                },
                () -> admin.send(
                                "POST",
                                "/api/admin/users",
                                "{\"loginId\":\"approval_claim\",\"displayName\":\"직원\",\"role\":\"SECURITY_STAFF\"}",
                                true)
                        .statusCode());
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        int reserved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM merchant_applications WHERE requested_login_id='approval_claim'", Integer.class);
        int issued = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_users WHERE login_id='approval_claim'", Integer.class);
        assertThat(reserved + issued).isEqualTo(1);
    }
    // 이전 양식의 아이디 없는 신청을 관리자가 임의 아이디로 승인할 수 없다.
    @Test
    void approve_whenLegacyApplicationHasNoLogin_requiresNewApplication() throws Exception {
        Long id = application(true, null);
        var result = approve(id);
        assertThat(result.statusCode()).isEqualTo(409);
        assertThat(result.body()).contains("APPLICATION_019");
        assertThat(saved(id).getStatus()).isEqualTo(ApplicationStatus.REVIEW_PENDING);
        assertThat(saved(id).getUserId()).isNull();
        verifyNoInteractions(mail);
        assertThat(json.readTree(admin.send("GET", BASE + "/" + id, null, false).body())
                        .get("requestedLoginId")
                        .isNull())
                .isTrue();
    }

    @MockitoSpyBean
    ApplicationMailDeliveryRepository mailDeliveries;

    @Autowired
    ApplicationRetentionService retention;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    java.time.Clock identityClock;

    long latestDelivery(Long id) {
        return jdbc.queryForObject(
                "SELECT MAX(delivery_id) FROM application_mail_deliveries WHERE application_id=?", Long.class, id);
    }

    void allowRecovery(Long id) {
        jdbc.update(
                "UPDATE application_mail_deliveries SET started_at=UTC_TIMESTAMP(6)-INTERVAL 6 MINUTE WHERE application_id=?",
                id);
    }

    HttpResponse<String> resend(Browser browser, Long id, long expected) throws Exception {
        return browser.send(
                "POST",
                BASE + "/" + id + "/mail-resends",
                json.writeValueAsString(Map.of("expectedDeliveryId", expected)),
                true);
    }

    // 재발송은 계정을 추가하지 않고 옛 링크를 폐기하며 같은 요청 반복을 거절한다.
    @Test
    void recovery_whenApproved_replacesLinkWithoutProvisioningAgain() throws Exception {
        Long id = application(true);
        assertThat(approve(id).statusCode()).isEqualTo(200);
        String old = raw();
        long expected = latestDelivery(id);
        int merchants = count("merchants"),
                users = count("platform_users"),
                permissions = count("user_permissions"),
                histories = count("admin_change_histories");
        allowRecovery(id);
        assertThat(resend(admin, id, expected).statusCode()).isEqualTo(200);
        String replacement = raw();
        assertThat(replacement).isNotEqualTo(old);
        assertThat(count("merchants")).isEqualTo(merchants);
        assertThat(count("platform_users")).isEqualTo(users);
        assertThat(count("user_permissions")).isEqualTo(permissions);
        assertThat(count("admin_change_histories")).isEqualTo(histories);
        assertThat(resend(admin, id, expected).statusCode()).isEqualTo(409);
        var visitor = new Browser();
        assertThat(visitor.send("POST", SETUP, setupBody(old, "OwnerNewPass123!"), true)
                        .body())
                .contains("APPLICATION_014");
        assertThat(visitor.send("POST", SETUP, setupBody(replacement, "OwnerNewPass123!"), true)
                        .statusCode())
                .isEqualTo(204);
        allowRecovery(id);
        assertThat(resend(admin, id, latestDelivery(id)).statusCode()).isEqualTo(409);
    }

    // 초기화로 폐기된 첫 설정은 강제 변경 플래그가 켜져 있어도 다시 발급하지 않는다.
    @Test
    void recovery_whenReset_revokesRecoveryEligibility() throws Exception {
        Long id = application(true);
        approve(id);
        allowRecovery(id);
        assertThat(admin.send("POST", "/api/admin/users/" + saved(id).getUserId() + "/password-reset", "{}", true)
                        .statusCode())
                .isEqualTo(200);
        clearInvocations(mail);
        assertThat(resend(admin, id, latestDelivery(id)).statusCode()).isEqualTo(409);
        verifyNoInteractions(mail);
        assertThat(admin.send("GET", BASE + "/" + id, null, false).body()).contains("SETUP_REVOKED");
    }

    // 서로 다른 관리자의 동시 복구는 한 발송 시도만 추가한다.
    @Test
    void recovery_whenConcurrentAcrossAdmins_createsOneAttempt() throws Exception {
        Long id = application(true);
        approve(id);
        allowRecovery(id);
        long expected = latestDelivery(id);
        var other = new Browser();
        other.login("approval_admin2");
        var outcomes = concurrent(
                () -> resend(admin, id, expected).statusCode(),
                () -> resend(other, id, expected).statusCode());
        assertThat(outcomes).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM application_mail_deliveries WHERE application_id=?", Integer.class, id))
                .isEqualTo(2);
    }

    // 메일 실패여도 발급과 새 토큰은 유지하며 옛 링크는 되살리지 않는다.
    @Test
    void recovery_whenSmtpFails_preservesIssuanceAndNewAttempt() throws Exception {
        Long id = application(true);
        approve(id);
        String old = raw();
        allowRecovery(id);
        doThrow(new org.springframework.mail.MailPreparationException("synthetic"))
                .when(mail)
                .sendApproval(anyString(), anyString(), anyString());
        var result = resend(admin, id, latestDelivery(id));
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).contains("FAILED");
        assertThat(saved(id).getUserId()).isNotNull();
        assertThat(tokens.findByTokenHash(ApplicationTokenCodec.hash(old))
                        .orElseThrow()
                        .getRevokedAt())
                .isNotNull();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM application_tokens WHERE application_id=? AND revoked_at IS NULL",
                        Integer.class,
                        id))
                .isEqualTo(1);
    }

    // 최신 결과만 메일 필터에 반영하고 오래된 PENDING은 조회에서만 UNKNOWN으로 표시한다.
    @Test
    void mailQuery_whenOldPending_filtersWithoutMutatingStoredState() throws Exception {
        Long id = application(true);
        approve(id);
        allowRecovery(id);
        jdbc.update(
                "UPDATE application_mail_deliveries SET status='PENDING',completed_at=NULL WHERE application_id=?", id);
        var filtered = admin.send("GET", BASE + "?mailAttention=true", null, false);
        assertThat(filtered.statusCode()).isEqualTo(200);
        assertThat(filtered.body()).contains("UNKNOWN", "PENDING", "달콤한 가게");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM application_mail_deliveries WHERE delivery_id=?",
                        String.class,
                        latestDelivery(id)))
                .isEqualTo("PENDING");
        assertThat(resend(admin, id, latestDelivery(id)).statusCode()).isEqualTo(200);
        assertThat(json.readTree(admin.send("GET", BASE + "?mailAttention=true", null, false)
                                .body())
                        .get("totalElements")
                        .asInt())
                .isZero();
        assertThat(json.readTree(admin.send("GET", BASE + "/" + id + "/mail-deliveries?size=1", null, false)
                                .body())
                        .get("totalElements")
                        .asInt())
                .isEqualTo(2);
    }

    // 승인 안내 재발송은 1분과 하루 다섯 번 제한 및 대기 시각을 제공한다.
    @Test
    void recovery_whenLimited_returnsRetryAfterAndDoesNotAppend() throws Exception {
        Long id = application(true);
        approve(id);
        var immediate = resend(admin, id, latestDelivery(id));
        assertThat(immediate.statusCode()).isEqualTo(429);
        assertThat(immediate.headers().firstValue("Retry-After")).isPresent();
        assertThat(immediate.body()).contains("APPLICATION_021", "nextResendAt");
        for (int i = 0; i < 5; i++) {
            allowRecovery(id);
            assertThat(resend(admin, id, latestDelivery(id)).statusCode()).isEqualTo(200);
        }
        allowRecovery(id);
        assertThat(resend(admin, id, latestDelivery(id)).statusCode()).isEqualTo(429);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM application_mail_deliveries WHERE application_id=?", Integer.class, id))
                .isEqualTo(6);
    }

    // 삭제된 발송의 지연 결과와 중복 완료는 행을 만들거나 기존 결과를 덮지 않는다.
    @Test
    void completion_whenDeletedOrAlreadyCompleted_isIdempotent() throws Exception {
        Long id = application(true);
        approve(id);
        long delivery = latestDelivery(id);
        persistence.complete(delivery, ApplicationMailStatus.FAILED, "SMTP_REJECTED");
        assertThat(mailDeliveries.findById(delivery).orElseThrow().getStatus()).isEqualTo(ApplicationMailStatus.SENT);
        jdbc.update("DELETE FROM application_mail_deliveries WHERE delivery_id=?", delivery);
        persistence.complete(delivery, ApplicationMailStatus.SENT, null);
        assertThat(mailDeliveries.findById(delivery)).isEmpty();
    }

    // 미확인과 반려 신청만 기한에 맞춰 자식부터 삭제하고 승인·검토 대기를 보존한다.
    @Test
    void retention_whenAtBoundary_deletesChildrenAndReleasesReservation() throws Exception {
        var fixed = Instant.parse("2026-11-30T00:00:00Z");
        doReturn(fixed).when(identityClock).instant();
        var now = LocalDateTime.ofInstant(fixed, ZoneOffset.UTC);
        Long approved = application(true);
        approve(approved);
        Long expired = applications
                .saveAndFlush(MerchantApplication.submit(
                        "만료",
                        "9999999999",
                        "담당자",
                        "expired@example.com",
                        "01011112222",
                        "expiry_owner",
                        null,
                        "v1",
                        now.minusDays(7)))
                .getApplicationId();
        var token = tokens.saveAndFlush(ApplicationToken.emailVerification(
                expired, ApplicationTokenCodec.hash("x".repeat(43)), now.minusDays(7)));
        mailDeliveries.saveAndFlush(
                ApplicationMailDelivery.verification(expired, token.getTokenId(), now.minusDays(7)));
        Long rejected = applications
                .saveAndFlush(MerchantApplication.submit(
                        "반려",
                        "8888888888",
                        "담당자",
                        "rejected@example.com",
                        "01011112222",
                        "rejected_owner",
                        null,
                        "v1",
                        now.minusDays(60)))
                .getApplicationId();
        jdbc.update(
                "UPDATE merchant_applications SET status='REJECTED',reviewed_at=? WHERE application_id=?",
                now.minusDays(30),
                rejected);
        Long reviewed = applications
                .saveAndFlush(MerchantApplication.submit(
                        "검토",
                        "7777777777",
                        "담당자",
                        "review@example.com",
                        "01011112222",
                        "review_owner",
                        null,
                        "v1",
                        now.minusDays(60)))
                .getApplicationId();
        jdbc.update("UPDATE merchant_applications SET status='REVIEW_PENDING' WHERE application_id=?", reviewed);
        retention.clean();
        assertThat(applications.existsById(expired)).isFalse();
        assertThat(applications.existsById(rejected)).isFalse();
        assertThat(applications.existsById(approved)).isTrue();
        assertThat(applications.existsById(reviewed)).isTrue();
        assertThat(tokens.findById(token.getTokenId())).isEmpty();
        assertThat(applications.findReservedLoginId("expiry_owner")).isEmpty();
    }

    // 전역 CSRF가 꺼져 있어도 복구는 보호하고 config는 공개하며 잘못된 페이지를 거절한다.
    @Test
    void recoveryHttp_whenUnauthorizedOrInvalid_rejectsBeforeSending() throws Exception {
        Long id = application(true);
        approve(id);
        allowRecovery(id);
        String body = "{\"expectedDeliveryId\":" + latestDelivery(id) + "}";
        assertThat(admin.send("POST", BASE + "/" + id + "/mail-resends", body, false)
                        .statusCode())
                .isEqualTo(403);
        var visitor = new Browser();
        assertThat(visitor.send("POST", BASE + "/" + id + "/mail-resends", body, true)
                        .statusCode())
                .isEqualTo(401);
        assertThat(admin.send("POST", BASE + "/" + id + "/mail-resends", "{}", true)
                        .body())
                .contains("APPLICATION_016");
        assertThat(admin.send("GET", BASE + "/" + id + "/mail-deliveries?page=2147483647", null, false)
                        .statusCode())
                .isEqualTo(400);
        assertThat(admin.send("GET", BASE + "?mailAttention=wrong", null, false).statusCode())
                .isEqualTo(400);
        var config = visitor.send("GET", "/api/merchant-applications/config", null, false);
        assertThat(config.statusCode()).isEqualTo(200);
        assertThat(config.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(config.body()).contains("true");
    }

    // 새 발송 행 저장 실패는 토큰 폐기도 롤백하고 SMTP를 호출하지 않는다.
    @Test
    void recovery_whenPreparationFails_rollsBackTokenAndSkipsSmtp() throws Exception {
        Long id = application(true);
        approve(id);
        allowRecovery(id);
        String old = raw();
        int before = count("application_tokens");
        long expected = latestDelivery(id);
        clearInvocations(mail);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic"))
                .when(mailDeliveries)
                .saveAndFlush(any(ApplicationMailDelivery.class));
        assertThat(resend(admin, id, expected).statusCode()).isEqualTo(503);
        assertThat(count("application_tokens")).isEqualTo(before);
        assertThat(tokens.findByTokenHash(ApplicationTokenCodec.hash(old))
                        .orElseThrow()
                        .getRevokedAt())
                .isNull();
        assertThat(latestDelivery(id)).isEqualTo(expected);
        verifyNoInteractions(mail);
    }

    // 삭제 중 자식 정리 실패는 해당 신청을 보존하고 뒤의 정상 신청은 계속 삭제한다.
    @Test
    void retention_whenOneDeleteFails_rollsBackAndContinuesBeyondBatch() {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        Long failed = null;
        for (int i = 0; i < 102; i++) {
            var a = applications.saveAndFlush(MerchantApplication.submit(
                    "만료",
                    String.format("6%09d", i),
                    "담당",
                    "batch" + i + "@example.com",
                    "01011112222",
                    "batch_" + i,
                    null,
                    "v1",
                    now.minusDays(8)));
            var token = tokens.saveAndFlush(ApplicationToken.emailVerification(
                    a.getApplicationId(), ApplicationTokenCodec.hash("batch" + i), now.minusDays(8)));
            mailDeliveries.saveAndFlush(
                    ApplicationMailDelivery.verification(a.getApplicationId(), token.getTokenId(), now.minusDays(8)));
            if (i == 0) failed = a.getApplicationId();
        }
        doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic"))
                .when(tokens)
                .deleteForApplication(failed);
        retention.clean();
        assertThat(applications.findAll())
                .extracting(MerchantApplication::getApplicationId)
                .containsExactly(failed);
        assertThat(count("application_tokens")).isEqualTo(1);
        assertThat(count("application_mail_deliveries")).isEqualTo(1);
    }

    // 공개와 관리자 확인 메일은 하나의 이메일 제한을 공유한다.
    @Test
    void verificationRecovery_whenPublicAndAdminAlternate_sharesDailyLimit() throws Exception {
        var start = Instant.parse("2026-11-01T00:00:00Z");
        doReturn(start).when(identityClock).instant();
        doReturn(start.toEpochMilli()).when(identityClock).millis();
        var request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.0.2.200");
        applicationService.submit(
                com.donworry.identity.dto.MerchantApplicationRequests.SubmitRequest.builder()
                        .merchantName("신청")
                        .businessRegistrationNumber("5555555555")
                        .contactName("담당")
                        .email("shared@example.com")
                        .phone("01011112222")
                        .requestedLoginId("shared_owner")
                        .consent(true)
                        .consentVersion("2026-10-29.1")
                        .build(),
                request);
        Long id = applications.findAll().getFirst().getApplicationId();
        assertThat(resend(admin, id, latestDelivery(id)).statusCode()).isEqualTo(429);
        for (int i = 1; i <= 5; i++) {
            var time = start.plusSeconds(61L * i);
            doReturn(time).when(identityClock).instant();
            doReturn(time.toEpochMilli()).when(identityClock).millis();
            if (i % 2 == 1)
                assertThat(resend(admin, id, latestDelivery(id)).statusCode()).isEqualTo(200);
            else
                applicationService.resend(
                        new com.donworry.identity.dto.MerchantApplicationRequests.ResendRequest("shared@example.com"),
                        request);
        }
        var time = start.plusSeconds(61 * 6);
        doReturn(time).when(identityClock).instant();
        doReturn(time.toEpochMilli()).when(identityClock).millis();
        assertThat(resend(admin, id, latestDelivery(id)).statusCode()).isEqualTo(429);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM application_mail_deliveries WHERE application_id=?", Integer.class, id))
                .isEqualTo(6);
    }

    // 반려 메일 복구는 사유와 검토 시각을 바꾸지 않고 새 시도만 생성한다.
    @Test
    void rejectionRecovery_whenAllowed_keepsReviewAndReason() throws Exception {
        Long id = application(true);
        assertThat(admin.send("POST", BASE + "/" + id + "/reject", "{\"reason\":\"사업자번호 확인 필요\"}", true)
                        .statusCode())
                .isEqualTo(200);
        var reviewed = saved(id).getReviewedAt();
        allowRecovery(id);
        assertThat(resend(admin, id, latestDelivery(id)).statusCode()).isEqualTo(200);
        assertThat(saved(id).getReviewedAt()).isEqualTo(reviewed);
        verify(mail, times(2)).sendRejection("approval@example.com", "사업자번호 확인 필요");
        assertThat(saved(id).getUserId()).isNull();
    }

    @MockitoSpyBean
    ApplicationMailQueryService mailQuery;

    // 복구가 계정 잠금을 먼저 얻으면 뒤의 초기화가 새 링크도 함께 폐기한다.
    @Test
    void recovery_whenResetWaitsForRecovery_revokesReplacementAfterCommit() throws Exception {
        Long id = application(true);
        approve(id);
        allowRecovery(id);
        var resetter = new Browser();
        resetter.login("approval_admin2");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
                    if (org.springframework.transaction.support.TransactionSynchronizationManager
                                    .isActualTransactionActive()
                            && once.compareAndSet(false, true)) {
                        entered.countDown();
                        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    }
                    return invocation.callRealMethod();
                })
                .when(mailQuery)
                .availability(any(MerchantApplication.class), any(LocalDateTime.class), eq(true));
        long expected = latestDelivery(id);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var recovery = executor.submit(() -> resend(admin, id, expected));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var reset = executor.submit(() ->
                    resetter.send("POST", "/api/admin/users/" + saved(id).getUserId() + "/password-reset", "{}", true));
            release.countDown();
            assertThat(recovery.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(reset.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        } finally {
            release.countDown();
        }
        assertThat(tokens.findByApplicationIdAndPurpose(id, ApplicationTokenPurpose.INITIAL_PASSWORD_SETUP))
                .allMatch(t -> t.getRevokedAt() != null);
    }

    // 초기화가 먼저 진행 중이면 복구는 커밋을 기다린 뒤 새 링크를 발급하지 않는다.
    @Test
    void recovery_whenResetOwnsAccountLock_rejectsAfterResetCommit() throws Exception {
        Long id = application(true);
        approve(id);
        allowRecovery(id);
        var resetter = new Browser();
        resetter.login("approval_admin2");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    entered.countDown();
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    return invocation.callRealMethod();
                })
                .when(org.springframework.test.util.AopTestUtils.<AdminChangeHistoryService>getUltimateTargetObject(
                        histories))
                .recordUser(anyLong(), anyLong(), eq("PASSWORD_RESET"), any(), any());
        long expected = latestDelivery(id);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var reset = executor.submit(() ->
                    resetter.send("POST", "/api/admin/users/" + saved(id).getUserId() + "/password-reset", "{}", true));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var recovery = executor.submit(() -> resend(admin, id, expected));
            release.countDown();
            assertThat(reset.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(recovery.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(409);
        } finally {
            release.countDown();
        }
        assertThat(latestDelivery(id)).isEqualTo(expected);
    }

    private List<Integer> concurrent(Callable<Integer> one, Callable<Integer> two) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var go = new CountDownLatch(1);
            var a = executor.submit(() -> {
                go.await();
                return one.call();
            });
            var b = executor.submit(() -> {
                go.await();
                return two.call();
            });
            go.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    private class Browser {
        final CookieManager cookies = new CookieManager();
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

        Browser() throws Exception {
            send("GET", "/api/auth/csrf", null, false);
        }

        void login(String id) throws Exception {
            login(id, "Approval123!");
        }

        void login(String id, String password) throws Exception {
            assertThat(send(
                                    "POST",
                                    "/api/auth/login",
                                    json.writeValueAsString(Map.of("loginId", id, "password", password)),
                                    true)
                            .statusCode())
                    .isEqualTo(200);
            send("GET", "/api/auth/csrf", null, false);
        }

        HttpResponse<String> send(String method, String path, String body, boolean csrf) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .method(
                            method,
                            body == null
                                    ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers.ofString(body));
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
    }
}
