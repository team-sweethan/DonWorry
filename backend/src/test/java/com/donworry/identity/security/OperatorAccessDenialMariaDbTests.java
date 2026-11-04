package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.donworry.HttpCsrfTestSupport;
import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.api.OperatorAccessDenied;
import com.donworry.identity.api.OperatorAccessDeniedType;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.repository.OperatorAccessDenialRepository;
import com.donworry.identity.service.OperatorAccessDenialRetentionService;
import com.donworry.identity.service.OperatorAccessDenialService;
import com.donworry.identity.service.UserSessionService;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({MariaDbTestContainerConfig.class, OperatorAccessDenialMariaDbTests.EventConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OperatorAccessDenialMariaDbTests {
    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JsonMapper mapper;

    @Autowired
    UserSessionService sessions;

    @Autowired
    OperatorAccessDenialService service;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    MockMvc mvc;

    @Autowired
    EventProbe probe;

    @MockitoSpyBean
    OperatorAccessDenialRepository denials;

    private String prefix;
    private long merchantId;
    private long ownerId;
    private HttpClient owner;

    @BeforeEach
    void setUp() throws Exception {
        prefix = "d525_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.update(
                "INSERT INTO merchants(merchant_code,display_name,status,pg_fee_rate,created_at) VALUES (?,?,'ACTIVE',0.02,UTC_TIMESTAMP(6))",
                prefix,
                prefix);
        merchantId = jdbc.queryForObject("SELECT merchant_id FROM merchants WHERE merchant_code=?", Long.class, prefix);
        ownerId = account("owner", "OPERATOR", false);
        owner = login("owner");
        probe.committedEvents().clear();
        probe.failCommit(false);
    }

    private long account(String suffix, String role, boolean forced) {
        jdbc.update(
                "INSERT INTO platform_users(login_id,password_hash,display_name,status,role,merchant_id,created_at,password_change_required) VALUES (?,?,'403 test','ACTIVE',?,?,UTC_TIMESTAMP(6),?)",
                prefix + suffix,
                encoder.encode("DenialPass123"),
                role,
                role.equals("OPERATOR") ? merchantId : null,
                forced);
        return jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id=?", Long.class, prefix + suffix);
    }

    private HttpClient login(String suffix) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        var result = HttpCsrfTestSupport.send(
                client,
                HttpRequest.newBuilder(uri("/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                                Map.of("loginId", prefix + suffix, "password", "DenialPass123")))));
        assertThat(result.statusCode()).isEqualTo(200);
        return client;
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> getAs(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private int count() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM operator_access_denials WHERE merchant_id=?", Integer.class, merchantId);
    }

    @AfterEach
    void cleanUp() {
        reset(denials);
        probe.failCommit(false);
        for (long id : jdbc.queryForList(
                "SELECT user_id FROM platform_users WHERE login_id LIKE ?", Long.class, prefix + "%")) {
            sessions.expireAll(id);
            jdbc.update("DELETE FROM operator_access_denials WHERE user_id=?", id);
            jdbc.update("DELETE FROM login_histories WHERE user_id=?", id);
            jdbc.update("DELETE FROM user_permissions WHERE user_id=?", id);
            jdbc.update("DELETE FROM platform_users WHERE user_id=?", id);
        }
        jdbc.update("DELETE FROM merchants WHERE merchant_id=?", merchantId);
        for (var event : probe.committedEvents())
            jdbc.update(
                    "DELETE FROM EVENT_PUBLICATION WHERE EVENT_TYPE=? AND SERIALIZED_EVENT LIKE ?",
                    OperatorAccessDenied.class.getName(),
                    "%\"eventId\":" + event.eventId() + ",%");
        probe.committedEvents().clear();
    }

    // 관리자·직원·없는 경로의 실제 HTTP 거절은 커밋된 원본과 동일한 이벤트 한 건을 만든다
    @ParameterizedTest
    @ValueSource(strings = {"/api/admin/users", "/api/security-cases", "/api/unknown-525"})
    void request_whenOutOfRole_commitsOneSourceAndEvent(String path) throws Exception {
        var response = getAs(owner, path + "?private=value");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("AUTH_002");
        assertThat(count()).isEqualTo(1);
        var event = onlyEvent();
        assertThat(event.deniedType()).isEqualTo(OperatorAccessDeniedType.OUT_OF_ROLE);
        assertThat(event.path()).isEqualTo(path);
        assertStored(event);
    }

    // 사장님 정산 경로는 권한이 없으면 동일한 403이지만 권한 부족 이벤트로 구분한다
    @Test
    void request_whenFinancePermissionMissing_recordsPermissionMissing() throws Exception {
        assertThat(getAs(owner, "/api/v1/settlements/report").statusCode()).isEqualTo(403);
        assertThat(onlyEvent().deniedType()).isEqualTo(OperatorAccessDeniedType.PERMISSION_MISSING);
    }

    // CSRF·직원·비로그인·강제 비밀번호 변경 거절은 수집하지 않는다
    @Test
    void request_whenExcluded_doesNotRecord() throws Exception {
        var csrf = owner.send(
                HttpRequest.newBuilder(uri("/api/admin/users"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(csrf.statusCode()).isEqualTo(403);
        assertThat(csrf.body()).contains("CSRF_001");
        assertThat(getAs(HttpClient.newHttpClient(), "/api/admin/users").statusCode())
                .isEqualTo(401);
        account("admin", "ADMIN", false);
        account("staff", "SECURITY_STAFF", false);
        account("forced", "OPERATOR", true);
        assertThat(getAs(login("admin"), "/api/unknown-525").statusCode()).isEqualTo(403);
        assertThat(getAs(login("staff"), "/api/admin/users").statusCode()).isEqualTo(403);
        assertThat(getAs(login("forced"), "/api/admin/users").statusCode()).isEqualTo(403);
        assertThat(count()).isZero();
        assertThat(probe.committedEvents()).isEmpty();
    }

    // INSERT 장애가 나도 403 AUTH_002를 유지하며 이벤트를 전달하지 않는다
    @Test
    void request_whenInsertFails_keepsForbiddenWithoutEvent() throws Exception {
        doThrow(new DataAccessResourceFailureException("private DB value"))
                .when(denials)
                .saveAndFlush(any());
        var response = getAs(owner, "/api/admin/users");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("AUTH_002").doesNotContain("private DB value");
        assertThat(count()).isZero();
        assertThat(probe.committedEvents()).isEmpty();
    }

    // INSERT와 발행 뒤 커밋이 실패하면 원본·발행 기록이 함께 롤백되고 응답만 403으로 유지한다
    @Test
    void request_whenCommitFails_rollsBackSourceAndPublication() throws Exception {
        int previous = jdbc.queryForObject(
                "SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE EVENT_TYPE=?",
                Integer.class,
                OperatorAccessDenied.class.getName());
        probe.failCommit(true);
        var response = getAs(owner, "/api/admin/users");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("AUTH_002");
        assertThat(count()).isZero();
        assertThat(probe.committedEvents()).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE EVENT_TYPE=?",
                        Integer.class,
                        OperatorAccessDenied.class.getName()))
                .isEqualTo(previous);
    }

    // 잘못된 세션 가맹점·아이디 대신 DB 계정 값으로 저장·발행한다
    @Test
    void request_whenSessionMetadataStale_usesDatabaseAccount() throws Exception {
        var user = SessionUser.builder()
                .userId(ownerId)
                .loginId("stale")
                .role(UserRole.OPERATOR)
                .merchantId(-1L)
                .permissions(List.of())
                .build();
        mvc.perform(get("/api/admin/users")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()))))
                .andExpect(status().isForbidden());
        var event = onlyEvent();
        assertThat(event.merchantId()).isEqualTo(merchantId);
        assertThat(event.loginId()).isEqualTo(prefix + "owner");
        assertStored(event);
    }

    // 경로는 512 Unicode 문자로 자르고 쿼리·컨텍스트 경로를 제외하며 IP를 공통 해석기로 구한다
    @Test
    void record_whenLongUnicodePath_preservesValidCharactersWithoutQuery() {
        var request = new MockHttpServletRequest("GET", "/context/ignored");
        request.setContextPath("/context");
        request.setServletPath("/" + "가😀".repeat(400));
        request.setQueryString("secret=private");
        request.setRemoteAddr("203.0.113.8");
        request.addHeader("X-Forwarded-For", "198.51.100.1");
        service.record(ownerId, OperatorAccessDeniedType.OUT_OF_ROLE, request);
        var event = onlyEvent();
        assertThat(event.path().codePointCount(0, event.path().length())).isEqualTo(512);
        assertThat(event.path()).doesNotContain("secret", "context", "ignored");
        assertThat(event.clientIp()).isEqualTo("203.0.113.8");
        assertThat(event.occurredAt().getNano() % 1000).isZero();
        assertStored(event);
    }

    // 사용자 정의 메서드 길이로 원본 수집이 누락되지 않고 이벤트도 같은 값을 전달한다
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "MKCALENDAR,MKCALENDAR",
        "LONGMETHOD1,LONGMETHOD",
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ,ABCDEFGHIJ"
    })
    void record_whenMethodExceedsColumn_preservesSourceAndBoundedEvent(String method, String expected) {
        var request = new MockHttpServletRequest(method, "/api/admin/users");
        request.setServletPath("/api/admin/users");
        service.record(ownerId, OperatorAccessDeniedType.OUT_OF_ROLE, request);
        assertThat(count()).isEqualTo(1);
        assertThat(onlyEvent().httpMethod()).isEqualTo(expected);
        assertStored(onlyEvent());
    }

    // 현재 기본 방화벽은 사용자 정의 메서드를 권한 핸들러 전에 차단한다
    @Test
    void request_whenCustomMethod_rejectedBeforeAuthorizationHandler() throws Exception {
        var response = HttpCsrfTestSupport.send(
                owner,
                HttpRequest.newBuilder(uri("/api/admin/users"))
                        .method("LONGMETHOD1", HttpRequest.BodyPublishers.noBody()));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(count()).isZero();
        assertThat(probe.committedEvents()).isEmpty();
    }

    // 실제 HTTP의 긴 미등록 경로도 403을 유지하고 저장 전에 길이를 제한한다
    @Test
    void request_whenLongPath_recordsTruncatedPath() throws Exception {
        String path = "/" + "x".repeat(700);
        assertThat(getAs(owner, path + "?token=excluded").statusCode()).isEqualTo(403);
        assertThat(onlyEvent().path()).isEqualTo(path.substring(0, 512));
        assertStored(onlyEvent());
    }

    // DB가 사용자·가맹점 참조와 거절 종류를 강제한다
    @Test
    void schema_whenInvalidReferenceOrType_rejectsInsert() {
        String sql =
                "INSERT INTO operator_access_denials(user_id,merchant_id,http_method,path,denied_type,occurred_at) VALUES (?,?,'GET','/test',?,UTC_TIMESTAMP(6))";
        assertThatThrownBy(() -> jdbc.update(sql, Long.MAX_VALUE, merchantId, "OUT_OF_ROLE"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, ownerId, Long.MAX_VALUE, "OUT_OF_ROLE"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, ownerId, merchantId, "UNKNOWN"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count()).isZero();
    }

    // 외부 트랜잭션이 롤백돼도 필터 기록의 독립 커밋을 취소하지 않는다
    @Test
    void record_whenCallerRollsBack_keepsIndependentCommit() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.record(
                    ownerId, OperatorAccessDeniedType.OUT_OF_ROLE, new MockHttpServletRequest("GET", "/ignored"));
            tx.setRollbackOnly();
        });
        assertThat(count()).isEqualTo(1);
        assertStored(onlyEvent());
    }

    // 없는 계정·DB 직원 계정으로는 사장님 원본을 만들지 않는다
    @Test
    void record_whenMissingOrStaff_doesNotPublish() {
        long adminId = account("admin", "ADMIN", false);
        for (Long id : new Long[] {null, Long.MAX_VALUE, adminId})
            service.record(id, OperatorAccessDeniedType.OUT_OF_ROLE, new MockHttpServletRequest());
        assertThat(count()).isZero();
        assertThat(probe.committedEvents()).isEmpty();
    }

    // 만료 행이 없어도 전체 원본을 읽지 않고 실제 IP 정리 SQL이 발생 시각 인덱스로 범위를 좁힌다
    @Test
    void retention_whenNoExpiredRows_usesTimeRangeWithoutFilesort() throws Exception {
        jdbc.update(
                "INSERT INTO operator_access_denials(user_id,merchant_id,http_method,path,client_ip,denied_type,occurred_at) SELECT ?,?,'GET','/plan','203.0.113.8','OUT_OF_ROLE','2026-11-01 00:00:00' FROM seq_1_to_10000",
                ownerId,
                merchantId);
        jdbc.execute("ANALYZE TABLE operator_access_denials");
        var query = OperatorAccessDenialRepository.class
                .getMethod("clearExpiredIpsBatch", String.class)
                .getAnnotation(org.springframework.data.jpa.repository.Query.class)
                .value();
        var plan = jdbc.queryForMap("EXPLAIN " + query.replace(":cutoff", "?"), "2000-01-01 00:00:00.000000");
        assertThat(plan.get("key")).isEqualTo("idx_operator_access_denials_retention");
        assertThat(plan.get("type")).isEqualTo("range");
        assertThat(String.valueOf(plan.get("Extra"))).doesNotContain("Using filesort");
        var columns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='operator_access_denials' AND INDEX_NAME='idx_operator_access_denials_retention' ORDER BY SEQ_IN_INDEX",
                String.class);
        assertThat(columns).containsExactly("occurred_at", "denial_id");
    }

    // 1년 경과한 IP만 비우고 경계 직전·NULL과 행의 다른 칸은 재실행해도 유지한다
    @Test
    void retention_whenAtYearBoundary_clearsOnlyExpiredIpsAndIsIdempotent() {
        var now = Instant.parse("2028-03-01T03:00:00.123456Z");
        var cutoff = LocalDateTime.ofInstant(now, ZoneOffset.UTC).minusYears(1);
        for (var timestamp : List.of(cutoff.minusNanos(1000), cutoff, cutoff.plusNanos(1000))) {
            jdbc.update(
                    "INSERT INTO operator_access_denials(user_id,merchant_id,http_method,path,client_ip,denied_type,occurred_at) VALUES (?,?,'GET','/test','203.0.113.8','OUT_OF_ROLE',?)",
                    ownerId,
                    merchantId,
                    timestamp);
        }
        jdbc.update(
                "INSERT INTO operator_access_denials(user_id,merchant_id,http_method,path,denied_type,occurred_at) VALUES (?,?,'POST','/null','PERMISSION_MISSING',?)",
                ownerId,
                merchantId,
                cutoff.minusDays(1));
        var before = jdbc.queryForList(
                "SELECT * FROM operator_access_denials WHERE merchant_id=? ORDER BY denial_id", merchantId);
        var cleanup = new OperatorAccessDenialRetentionService(denials, transactions, Clock.fixed(now, ZoneOffset.UTC));
        cleanup.clearExpiredIps();
        cleanup.clearExpiredIps();
        var after = jdbc.queryForList(
                "SELECT * FROM operator_access_denials WHERE merchant_id=? ORDER BY denial_id", merchantId);
        assertThat(after).hasSameSizeAs(before);
        for (int i = 0; i < before.size(); i++) {
            var expected = new HashMap<>(before.get(i));
            var occurredAt = jdbc.queryForObject(
                    "SELECT occurred_at FROM operator_access_denials WHERE denial_id=?",
                    LocalDateTime.class,
                    expected.get("denial_id"));
            if (!occurredAt.isAfter(cutoff)) expected.put("client_ip", null);
            assertThat(after.get(i)).isEqualTo(expected);
        }
        assertThat(after.get(2).get("client_ip")).isEqualTo("203.0.113.8");
    }

    // 한 배치가 실패해도 다음 실행에서 1,000건을 넘는 IP를 모두 비우고 원본 행은 보존한다
    @Test
    void retention_whenBatchFails_retriesAndClearsMultipleBatches() {
        var now = Instant.parse("2028-02-29T03:00:00.123456Z");
        var cutoff = LocalDateTime.ofInstant(now, ZoneOffset.UTC).minusYears(1);
        jdbc.batchUpdate(
                "INSERT INTO operator_access_denials(user_id,merchant_id,http_method,path,client_ip,denied_type,occurred_at) VALUES (?,?,'GET','/batch','203.0.113.9','OUT_OF_ROLE',?)",
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public int getBatchSize() {
                        return 1003;
                    }

                    @Override
                    public void setValues(java.sql.PreparedStatement statement, int i) throws java.sql.SQLException {
                        statement.setLong(1, ownerId);
                        statement.setLong(2, merchantId);
                        statement.setObject(3, cutoff);
                    }
                });
        var cleanup = new OperatorAccessDenialRetentionService(denials, transactions, Clock.fixed(now, ZoneOffset.UTC));
        doThrow(new DataAccessResourceFailureException("private DB value"))
                .when(denials)
                .clearExpiredIpsBatch(anyString());
        cleanup.clearExpiredIps();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(client_ip) FROM operator_access_denials WHERE merchant_id=?",
                        Integer.class,
                        merchantId))
                .isEqualTo(1003);
        reset(denials);
        cleanup.clearExpiredIps();
        assertThat(count()).isEqualTo(1003);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(client_ip) FROM operator_access_denials WHERE merchant_id=?",
                        Integer.class,
                        merchantId))
                .isZero();
    }

    private OperatorAccessDenied onlyEvent() {
        assertThat(probe.committedEvents()).hasSize(1);
        return probe.committedEvents().getFirst();
    }

    private void assertStored(OperatorAccessDenied event) {
        var row = jdbc.queryForMap("SELECT * FROM operator_access_denials WHERE denial_id=?", event.eventId());
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(event.userId());
        assertThat(((Number) row.get("merchant_id")).longValue()).isEqualTo(event.merchantId());
        assertThat(row.get("http_method")).isEqualTo(event.httpMethod());
        assertThat(row.get("path")).isEqualTo(event.path());
        assertThat(row.get("client_ip")).isEqualTo(event.clientIp());
        assertThat(row.get("denied_type")).isEqualTo(event.deniedType().name());
        assertThat(jdbc.queryForObject(
                        "SELECT occurred_at FROM operator_access_denials WHERE denial_id=?",
                        LocalDateTime.class,
                        event.eventId()))
                .isEqualTo(LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EventConfig {
        @Bean
        EventProbe denialEventProbe(JdbcTemplate jdbc) {
            return new EventProbe(jdbc);
        }
    }

    static class EventProbe {
        private final JdbcTemplate jdbc;
        final List<OperatorAccessDenied> committed = new CopyOnWriteArrayList<>();
        final AtomicBoolean failCommit = new AtomicBoolean();

        EventProbe(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        public List<OperatorAccessDenied> committedEvents() {
            return committed;
        }

        public void failCommit(boolean fail) {
            failCommit.set(fail);
        }

        @EventListener
        public void publishing(OperatorAccessDenied event) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM operator_access_denials WHERE denial_id=?",
                            Integer.class,
                            event.eventId()))
                    .isEqualTo(1);
            if (failCommit.get())
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        throw new DataAccessResourceFailureException("commit unavailable");
                    }
                });
        }

        @TransactionalEventListener
        public void committed(OperatorAccessDenied event) {
            committed.add(event);
        }
    }
}
