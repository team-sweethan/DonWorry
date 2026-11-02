package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.*;

import com.donworry.DonWorryApplication;
import com.donworry.identity.service.ProductionStartupService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.mariadb.MariaDBContainer;

@Tag("mariadb")
@ExtendWith(OutputCaptureExtension.class)
class ProductionSecurityMariaDbTests {
    // 운영 초기화가 공용 테스트 DB의 시드 비밀번호를 바꾸지 않도록 별도 컨테이너를 쓴다.
    private final MariaDBContainer database = new MariaDBContainer("mariadb:11.8");
    private static final String PASSWORD = "Production123!";

    @BeforeEach
    void startDatabase() {
        database.start();
    }

    @AfterEach
    void stopDatabase() {
        database.stop();
    }

    private SpringApplication application(String password) {
        return application(password, true);
    }

    private SpringApplication application(String password, boolean production) {
        var application = new SpringApplication(DonWorryApplication.class);
        if (production) application.setAdditionalProfiles("prod");
        var properties = new HashMap<String, Object>();
        properties.put("DONWORRY_DB_HOST", database.getHost());
        properties.put("DONWORRY_DB_PORT", database.getMappedPort(3306));
        properties.put("DONWORRY_DB_NAME", database.getDatabaseName());
        properties.put("DONWORRY_DB_USER", database.getUsername());
        properties.put("DONWORRY_DB_PASSWORD", database.getPassword());
        properties.put("DONWORRY_ADMIN_PASSWORD", password);
        properties.put("spring.datasource.url", database.getJdbcUrl());
        properties.put("spring.datasource.username", database.getUsername());
        properties.put("spring.datasource.password", database.getPassword());
        properties.put("server.port", 0);
        properties.put("spring.main.banner-mode", "off");
        properties.put("logging.level.root", "ERROR");
        application.setDefaultProperties(properties);
        // application.yml의 기본 포트나 개발자 환경변수가 격리된 검사 설정을 덮지 못하게 한다.
        application.addInitializers(context -> context.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("productionTest", properties)));
        return application;
    }

    private JdbcTemplate jdbc(ApplicationContext context) {
        return context.getBean(JdbcTemplate.class);
    }

    private String hash(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT password_hash FROM platform_users WHERE login_id='admin'", String.class);
    }

    private HttpResponse<String> post(ConfigurableApplicationContext context, String path, String body)
            throws Exception {
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        // 평문 테스트 서버에서도 운영 Secure 속성은 유지한다. 발급 쿠키를 명시적으로 돌려보낸다.
        var issued = get(context, "/api/auth/csrf");
        var token = issued.headers().allValues("set-cookie").stream()
                .flatMap(value -> java.net.HttpCookie.parse(value).stream())
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .findFirst()
                .orElseThrow();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + token.getValue())
                .header("X-XSRF-TOKEN", token.getValue())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(ConfigurableApplicationContext context, String path) throws Exception {
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(ConfigurableApplicationContext context, String loginId, String password)
            throws Exception {
        return post(context, "/api/auth/login", "{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\"}");
    }

    // DB 커밋 후에만 웹 서버 시작 이벤트가 발생하고 HTTP 인증·Secure 쿠키를 확인한다
    @Test
    void startup_whenProduction_commitsBeforeWebServerAndInactivatesDemoAccounts() throws Exception {
        var application = application(PASSWORD);
        var started = new AtomicBoolean();
        application.addListeners((ApplicationListener<WebServerInitializedEvent>) event -> {
            var context = event.getApplicationContext();
            assertThat(context.getBean(PasswordEncoder.class).matches(PASSWORD, hash(jdbc(context))))
                    .isTrue();
            assertThat(directJdbc()
                            .queryForObject(
                                    "SELECT COUNT(*) FROM platform_users WHERE login_id IN ('boss','staff_test','cakeshop_owner') AND status='INACTIVE'",
                                    Integer.class))
                    .isEqualTo(3);
            assertThat(directJdbc()
                            .queryForObject(
                                    "SELECT COUNT(*) FROM admin_change_histories WHERE change_type='PRODUCTION_DEMO_ACCOUNT_INACTIVATED'",
                                    Integer.class))
                    .isEqualTo(3);
            started.set(true);
        });
        String appliedHash;
        int historyCount;
        try (var context = application.run()) {
            assertThat(started).isTrue();
            var jdbc = jdbc(context);
            appliedHash = hash(jdbc);
            var response = login(context, "admin", PASSWORD);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"passwordChangeRequired\":false");
            assertThat(response.headers().allValues("set-cookie"))
                    .anySatisfy(cookie -> assertThat(cookie)
                            .startsWith("JSESSIONID=")
                            .contains("Secure", "HttpOnly", "SameSite=Lax"));
            assertThat(login(context, "admin", "1234").statusCode()).isEqualTo(401);
            // 프록시 전달 정보 없이 평문 HTTP로 요청해도 운영 XSRF-TOKEN은 Secure로 고정된다
            assertThat(get(context, "/api/auth/csrf").headers().allValues("set-cookie"))
                    .anySatisfy(cookie -> assertThat(cookie)
                            .startsWith("XSRF-TOKEN=")
                            .contains("Secure", "SameSite=Lax")
                            .doesNotContain("HttpOnly"));
            // 운영 기본값으로 인증 없는 시연용 API는 보안 설정에서 먼저 막힌다 (#392).
            // FDS Mock 컨트롤러도 등록되지 않아 기록이 남지 않는다
            assertThat(post(context, "/api/fds/evaluations/mock", "").statusCode())
                    .isEqualTo(401);
            assertThat(post(context, "/api/demo/held-payments", "{}").statusCode())
                    .isEqualTo(401);
            assertThat(jdbc(context).queryForObject("SELECT COUNT(*) FROM fds_evaluations", Integer.class))
                    .isZero();
            for (String demo : new String[] {"boss", "staff_test", "cakeshop_owner"}) {
                assertThat(login(context, demo, "1234").statusCode()).isEqualTo(401);
                assertThat(jdbc.queryForObject(
                                "SELECT status FROM platform_users WHERE login_id=?", String.class, demo))
                        .isEqualTo("INACTIVE");
            }
            assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM admin_change_histories WHERE change_type='PRODUCTION_PASSWORD_APPLIED'",
                            Integer.class))
                    .isPositive();
            var history = jdbc.queryForObject(
                    "SELECT after_data FROM admin_change_histories WHERE change_type='PRODUCTION_PASSWORD_APPLIED' ORDER BY history_id DESC LIMIT 1",
                    String.class);
            assertThat(history).contains("PRODUCTION_STARTUP").doesNotContain(PASSWORD, appliedHash);
            historyCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM admin_change_histories WHERE change_type='PRODUCTION_PASSWORD_APPLIED'",
                    Integer.class);
            var demoHistories = jdbc.queryForList(
                    "SELECT actor_user_id,target_user_id,before_data,after_data FROM admin_change_histories WHERE change_type='PRODUCTION_DEMO_ACCOUNT_INACTIVATED'");
            assertThat(demoHistories).hasSize(3);
            Long adminId = jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id='admin'", Long.class);
            for (var entry : demoHistories) {
                assertThat(((Number) entry.get("actor_user_id")).longValue()).isEqualTo(adminId);
                assertThat(jdbc.queryForObject(
                                "SELECT login_id FROM platform_users WHERE user_id=?",
                                String.class,
                                entry.get("target_user_id")))
                        .isIn("boss", "staff_test", "cakeshop_owner");
                assertThat(entry.get("before_data").toString()).contains("ACTIVE");
                assertThat(entry.get("after_data").toString())
                        .contains("INACTIVE", "PRODUCTION_STARTUP")
                        .doesNotContain(PASSWORD, appliedHash);
            }
            context.getBean(ProductionStartupService.class).initialize(PASSWORD);
            assertThat(hash(jdbc)).isEqualTo(appliedHash);
        }
        try (var restarted = application(PASSWORD).run()) {
            assertThat(hash(jdbc(restarted))).isEqualTo(appliedHash);
            assertThat(jdbc(restarted)
                            .queryForObject(
                                    "SELECT COUNT(*) FROM admin_change_histories WHERE change_type='PRODUCTION_PASSWORD_APPLIED'",
                                    Integer.class))
                    .isEqualTo(historyCount);
            assertThat(login(restarted, "admin", PASSWORD).statusCode()).isEqualTo(200);
            assertThat(jdbc(restarted)
                            .queryForObject(
                                    "SELECT COUNT(*) FROM admin_change_histories WHERE change_type='PRODUCTION_DEMO_ACCOUNT_INACTIVATED'",
                                    Integer.class))
                    .isEqualTo(3);
        }
    }

    // 이력 INSERT 오류는 비밀번호 변경도 롤백하고 기존 실패 횟수·잠금 시각을 보존한다
    @Test
    void initialize_whenHistoryInsertFails_rollsBackPasswordAndPreservesLock() {
        try (var context = application(PASSWORD).run()) {
            var jdbc = jdbc(context);
            var previousHash = hash(jdbc);
            jdbc.update(
                    "UPDATE platform_users SET login_failure_count=5, login_locked_until='2099-01-01 00:00:00' WHERE login_id='admin'");
            jdbc.execute(
                    "CREATE TRIGGER reject_production_history BEFORE INSERT ON admin_change_histories FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test history failure'");
            try {
                assertThatThrownBy(() ->
                                context.getBean(ProductionStartupService.class).initialize("Another123!"))
                        .isInstanceOf(RuntimeException.class);
                assertThat(hash(jdbc)).isEqualTo(previousHash);
                assertThat(jdbc.queryForObject(
                                "SELECT login_failure_count FROM platform_users WHERE login_id='admin'", Integer.class))
                        .isEqualTo(5);
                assertThat(jdbc.queryForObject(
                                "SELECT YEAR(login_locked_until) FROM platform_users WHERE login_id='admin'",
                                Integer.class))
                        .isEqualTo(2099);
                var failedStartup = application("Another123!");
                var webStarted = new AtomicBoolean();
                failedStartup.addListeners(
                        (ApplicationListener<WebServerInitializedEvent>) event -> webStarted.set(true));
                assertThatThrownBy(failedStartup::run).isInstanceOf(RuntimeException.class);
                assertThat(webStarted).isFalse();
                assertThat(hash(jdbc)).isEqualTo(previousHash);
            } finally {
                jdbc.execute("DROP TRIGGER reject_production_history");
                jdbc.update(
                        "UPDATE platform_users SET login_failure_count=0, login_locked_until=NULL WHERE login_id='admin'");
            }
        }
    }

    // 정책 위반 비밀번호는 실제 웹 서버가 시작되기 전에 기동 실패한다
    @Test
    void startup_whenPasswordInvalid_failsBeforeWebServerStarts(CapturedOutput output) {
        var application = application("invalid-secret");
        var started = new AtomicBoolean();
        application.addListeners((ApplicationListener<WebServerInitializedEvent>) event -> started.set(true));
        assertThatThrownBy(application::run).hasMessageContaining("비밀번호");
        assertThat(started).isFalse();
        assertThat(output.getAll()).doesNotContain("invalid-secret");
    }

    private JdbcTemplate directJdbc() {
        return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
    }

    // 동일 비밀번호인 관리자도 활성 대상은 정지하고 이미 정지되거나 없는 대상은 건너뛴다
    @Test
    void startup_whenPasswordUnchangedAndTargetsMixed_processesRemainingAccount() throws Exception {
        String previousHash;
        int previousHistories;
        try (var context = application(PASSWORD).run()) {
            var jdbc = jdbc(context);
            previousHash = hash(jdbc);
            previousHistories = jdbc.queryForObject("SELECT COUNT(*) FROM admin_change_histories", Integer.class);
            jdbc.update("UPDATE platform_users SET status='ACTIVE' WHERE login_id='boss'");
            jdbc.update("UPDATE platform_users SET login_id='ordinary_owner' WHERE login_id='cakeshop_owner'");
        }
        try (var context = application(PASSWORD).run()) {
            var jdbc = jdbc(context);
            assertThat(hash(jdbc)).isEqualTo(previousHash);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_change_histories", Integer.class))
                    .isEqualTo(previousHistories + 1);
            assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM platform_users WHERE login_id='cakeshop_owner'", Integer.class))
                    .isZero();
            assertThat(login(context, "boss", "1234").statusCode()).isEqualTo(401);
            assertThat(login(context, "staff_test", "1234").statusCode()).isEqualTo(401);
        }
    }

    // 두 번째 시연 계정 이력 실패도 관리자와 앞선 대상의 변경·이력 전체를 롤백하고 웹 서버를 시작하지 않는다
    @Test
    void startup_whenDemoHistoryFails_rollsBackEntireStartup() {
        try (var context = application(PASSWORD).run()) {
            var jdbc = jdbc(context);
            jdbc.update(
                    "UPDATE platform_users SET status='ACTIVE' WHERE login_id IN ('boss','staff_test','cakeshop_owner')");
            jdbc.update("UPDATE platform_users SET password_change_required=true WHERE login_id='admin'");
        }
        var jdbc = directJdbc();
        var beforeUsers = jdbc.queryForList("SELECT * FROM platform_users ORDER BY user_id");
        var beforeHistories = jdbc.queryForList("SELECT * FROM admin_change_histories ORDER BY history_id");
        jdbc.execute(
                "CREATE TRIGGER reject_demo_history BEFORE INSERT ON admin_change_histories FOR EACH ROW BEGIN IF NEW.change_type='PRODUCTION_DEMO_ACCOUNT_INACTIVATED' AND NEW.target_user_id=(SELECT user_id FROM platform_users WHERE login_id='staff_test') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test demo history failure'; END IF; END");
        try {
            var application = application("Another123!");
            var started = new AtomicBoolean();
            application.addListeners((ApplicationListener<WebServerInitializedEvent>) event -> started.set(true));
            assertThatThrownBy(application::run).isInstanceOf(RuntimeException.class);
            assertThat(started).isFalse();
            assertThat(jdbc.queryForList("SELECT * FROM platform_users ORDER BY user_id"))
                    .isEqualTo(beforeUsers);
            assertThat(jdbc.queryForList("SELECT * FROM admin_change_histories ORDER BY history_id"))
                    .isEqualTo(beforeHistories);
        } finally {
            jdbc.execute("DROP TRIGGER reject_demo_history");
        }
    }

    // 일반 로컬 시작에서는 시연 계정 로그인과 수동 정지를 유지하고 다른 활성 계정도 운영에서 사용할 수 있다
    @Test
    void startup_whenLocal_preservesDemoLoginAndManualInactivation() throws Exception {
        try (var context = application(PASSWORD, false).run()) {
            for (String demo : new String[] {"boss", "staff_test", "cakeshop_owner"}) {
                assertThat(login(context, demo, "1234").statusCode()).isEqualTo(200);
            }
            jdbc(context).update("UPDATE platform_users SET status='INACTIVE' WHERE login_id='staff_test'");
            jdbc(context).update("UPDATE platform_users SET login_id='ordinary_owner' WHERE login_id='cakeshop_owner'");
        }
        try (var context = application(PASSWORD, false).run()) {
            assertThat(login(context, "staff_test", "1234").statusCode()).isEqualTo(401);
            assertThat(login(context, "boss", "1234").statusCode()).isEqualTo(200);
            assertThat(jdbc(context)
                            .queryForObject(
                                    "SELECT COUNT(*) FROM admin_change_histories WHERE change_type LIKE 'PRODUCTION_%'",
                                    Integer.class))
                    .isZero();
        }
        try (var context = application(PASSWORD).run()) {
            assertThat(login(context, "ordinary_owner", "1234").statusCode()).isEqualTo(200);
            assertThat(login(context, "admin", PASSWORD).statusCode()).isEqualTo(200);
        }
    }
}
