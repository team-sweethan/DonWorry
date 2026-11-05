package com.donworry.fds.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.donworry.HttpCsrfTestSupport;
import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.service.OperatorAccessDetectionService;
import com.donworry.identity.api.OperatorAccessDenied;
import com.donworry.identity.api.OperatorAccessDeniedType;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

// #515 전체 흐름을 실제 HTTP 403부터 P1 원본, 이벤트, P2 탐지, 계정 정지까지 검증한다.
@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OperatorAccessDetectionFlowMariaDbTests {
    private static final String PASSWORD = "FlowPass123";
    private static final String OUT_OF_ROLE_PATH = "/api/admin/users";
    private static final String PERMISSION_MISSING_PATH = "/api/v1/settlements/report";
    private static final Duration WAIT = Duration.ofSeconds(15);

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JsonMapper mapper;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    OperatorAccessDetectionService detectionService;

    private final List<Boolean> outerTransactions = new CopyOnWriteArrayList<>();
    private final List<Long> merchantIds = new ArrayList<>();
    private final List<Account> accounts = new ArrayList<>();

    private record Account(long id, String loginId) {}

    @BeforeEach
    void setUp() {
        outerTransactions.clear();
        doAnswer(invocation -> {
                    outerTransactions.add(TransactionSynchronizationManager.isActualTransactionActive());
                    return invocation.callRealMethod();
                })
                .when(detectionService)
                .receive(any(OperatorAccessDenied.class));
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (var account : accounts) {
            awaitProcessed(account);
            jdbc.update(
                    "DELETE FROM EVENT_PUBLICATION WHERE EVENT_TYPE=? AND SERIALIZED_EVENT LIKE ?",
                    OperatorAccessDenied.class.getName(),
                    publicationPattern(account));
            jdbc.update(
                    "DELETE e FROM fds_operator_access_detection_evidences e JOIN fds_operator_access_detections d ON e.detection_id=d.operator_access_detection_id WHERE d.user_id=?",
                    account.id());
            jdbc.update("DELETE FROM fds_operator_access_detections WHERE user_id=?", account.id());
            jdbc.update("DELETE FROM fds_operator_access_events WHERE user_id=?", account.id());
            jdbc.update("DELETE FROM operator_access_denials WHERE user_id=?", account.id());
            jdbc.update(
                    "DELETE FROM admin_change_histories WHERE target_user_id=? OR actor_user_id=?",
                    account.id(),
                    account.id());
            jdbc.update("DELETE FROM login_histories WHERE user_id=?", account.id());
            jdbc.update("DELETE FROM platform_users WHERE user_id=?", account.id());
        }
        for (long merchantId : merchantIds) jdbc.update("DELETE FROM merchants WHERE merchant_id=?", merchantId);
        accounts.clear();
        merchantIds.clear();
    }

    // 관리자 경로를 기준 횟수만큼 호출하면 계정을 정지하고 로그인을 거절한다
    @Test
    void outOfRole_whenThresholdReached_suspendsAccountAndRejectsLogin() throws Exception {
        var owner = operator();
        var client = login(owner);

        callAndAwait(client, owner, OUT_OF_ROLE_PATH, 5, 0);

        awaitStatus(owner, "INACTIVE");
        assertThat(detections(owner)).isEqualTo(1);
        await().atMost(WAIT).until(() -> "SUCCEEDED".equals(lastActionStatus(owner)));
        assertThat(get(client, OUT_OF_ROLE_PATH).statusCode()).isEqualTo(401);
        assertThat(loginResponse(owner).statusCode()).isEqualTo(401);
    }

    // 권한 없는 정산 API는 높은 기준 미만이면 정지하지 않고 기준에 도달하면 정지한다
    @Test
    void permissionMissing_whenBelowHighThreshold_keepsAccountActiveUntilThreshold() throws Exception {
        var owner = operator();
        var client = login(owner);

        callAndAwait(client, owner, PERMISSION_MISSING_PATH, 29, 0);

        assertThat(status(owner)).isEqualTo("ACTIVE");
        assertThat(detections(owner)).isZero();

        callAndAwait(client, owner, PERMISSION_MISSING_PATH, 1, 29);

        awaitStatus(owner, "INACTIVE");
        assertThat(detections(owner)).isEqualTo(1);
    }

    // 관리자가 정지를 풀면 다시 로그인되고 이전 기록으로 재정지하지 않으며 새 기준 도달만 재정지한다
    @Test
    void release_whenAdminEnablesAccount_countsOnlyNewDenials() throws Exception {
        var owner = operator();
        var client = login(owner);
        callAndAwait(client, owner, OUT_OF_ROLE_PATH, 5, 0);
        awaitStatus(owner, "INACTIVE");
        await().atMost(WAIT)
                .until(() -> lastActionStatus(owner) != null && !"REQUESTED".equals(lastActionStatus(owner)));

        var released = HttpCsrfTestSupport.send(
                login(admin()),
                HttpRequest.newBuilder(uri("/api/admin/users/" + owner.id() + "/status"))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"status\":\"ACTIVE\"}")));
        assertThat(released.statusCode()).isEqualTo(204);

        var again = login(owner);
        callAndAwait(again, owner, OUT_OF_ROLE_PATH, 4, 5);
        assertThat(status(owner)).isEqualTo("ACTIVE");
        assertThat(detections(owner)).isEqualTo(1);

        callAndAwait(again, owner, OUT_OF_ROLE_PATH, 1, 9);
        awaitStatus(owner, "INACTIVE");
        assertThat(detections(owner)).isEqualTo(2);
    }

    // 기준 횟수 이상의 호출이 동시에 와도 계정을 정지하고 탐지 기록은 한 건만 남긴다
    @ParameterizedTest
    @ValueSource(ints = {5, 10})
    void outOfRole_whenConcurrentRequestsReachThreshold_recordsOneDetection(int calls) throws Exception {
        for (int round = 0; round < 5; round++) {
            var owner = operator();
            var client = login(owner);

            int forbidden = callConcurrently(client, calls);

            // 정지가 먼저 끝나면 늦은 요청은 세션이 끊겨 401을 받고 403 원본을 남기지 않는다.
            assertThat(forbidden).isGreaterThanOrEqualTo(5);
            await().atMost(WAIT).until(() -> denials(owner) == forbidden);
            awaitProcessed(owner);
            awaitStatus(owner, "INACTIVE");
            assertThat(detections(owner)).isEqualTo(1);
        }
    }

    // 트랜잭션 안에서 호출해도 수신 결과는 계정 잠금 안에서 먼저 커밋된다
    @Test
    void receive_whenCalledInsideTransaction_commitsBeforeReturning() throws Exception {
        var owner = operator();
        var event = new OperatorAccessDenied(
                ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE),
                owner.id(),
                owner.loginId(),
                merchantIds.getLast(),
                "GET",
                OUT_OF_ROLE_PATH,
                OperatorAccessDeniedType.OUT_OF_ROLE,
                Instant.now(),
                "127.0.0.1");

        int visible = new TransactionTemplate(transactionManager).execute(status -> {
            detectionService.receive(event);
            try (var reader = Executors.newSingleThreadExecutor()) {
                return reader.submit(() -> events(owner)).get();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });

        assertThat(visible).isEqualTo(1);
    }

    // 계정 잠금이 커밋까지 유지되도록 리스너는 바깥 트랜잭션 없이 탐지를 호출한다
    @Test
    void listener_whenEventArrives_receivesWithoutOuterTransaction() throws Exception {
        var owner = operator();

        callAndAwait(login(owner), owner, OUT_OF_ROLE_PATH, 1, 0);

        assertThat(outerTransactions).isNotEmpty().containsOnly(false);
        assertThat(publications(owner)).isPositive();
    }

    private void callAndAwait(HttpClient client, Account owner, String path, int count, int alreadyStored)
            throws Exception {
        for (int i = 1; i <= count; i++) {
            assertThat(get(client, path).statusCode()).isEqualTo(403);
            int expected = alreadyStored + i;
            await().atMost(WAIT).until(() -> events(owner) >= expected);
        }
        awaitProcessed(owner);
    }

    private int callConcurrently(HttpClient client, int count) throws Exception {
        try (var pool = Executors.newFixedThreadPool(count)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<Integer>>();
            Callable<Integer> call = () -> {
                start.await();
                return get(client, OUT_OF_ROLE_PATH).statusCode();
            };
            for (int i = 0; i < count; i++) results.add(pool.submit(call));
            start.countDown();
            int forbidden = 0;
            for (var result : results) {
                int status = result.get();
                assertThat(status).isIn(401, 403);
                if (status == 403) forbidden++;
            }
            return forbidden;
        }
    }

    // 모든 P1 원본의 이벤트 발행이 끝나야 탐지 건수와 정리 결과가 안정된다.
    private void awaitProcessed(Account account) throws Exception {
        await().atMost(WAIT).until(() -> events(account) >= denials(account) && incompletePublications(account) == 0);
    }

    private void awaitStatus(Account account, String expected) throws Exception {
        await().atMost(WAIT).until(() -> expected.equals(status(account)));
    }

    private Account operator() {
        String code = uniqueId();
        jdbc.update(
                "INSERT INTO merchants(merchant_code,display_name,status,pg_fee_rate,created_at) VALUES (?,?,'ACTIVE',0.02,UTC_TIMESTAMP(6))",
                code,
                code);
        long merchantId =
                jdbc.queryForObject("SELECT merchant_id FROM merchants WHERE merchant_code=?", Long.class, code);
        merchantIds.add(merchantId);
        return account("OPERATOR", merchantId);
    }

    private Account admin() {
        return account("ADMIN", null);
    }

    private Account account(String role, Long merchantId) {
        String loginId = uniqueId();
        jdbc.update(
                "INSERT INTO platform_users(login_id,password_hash,display_name,status,role,merchant_id,created_at,password_change_required) VALUES (?,?,'탐지 흐름','ACTIVE',?,?,UTC_TIMESTAMP(6),false)",
                loginId,
                encoder.encode(PASSWORD),
                role,
                merchantId);
        long userId = jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id=?", Long.class, loginId);
        var account = new Account(userId, loginId);
        accounts.add(account);
        return account;
    }

    private static String uniqueId() {
        return "f572_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private HttpClient login(Account account) throws Exception {
        var client = client();
        assertThat(login(client, account).statusCode()).isEqualTo(200);
        return client;
    }

    private HttpResponse<String> loginResponse(Account account) throws Exception {
        return login(client(), account);
    }

    private HttpResponse<String> login(HttpClient client, Account account) throws Exception {
        return HttpCsrfTestSupport.send(
                client,
                HttpRequest.newBuilder(uri("/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                                Map.of("loginId", account.loginId(), "password", PASSWORD)))));
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String status(Account account) {
        return jdbc.queryForObject("SELECT status FROM platform_users WHERE user_id=?", String.class, account.id());
    }

    private int denials(Account account) {
        return count("SELECT COUNT(*) FROM operator_access_denials WHERE user_id=?", account);
    }

    private int events(Account account) {
        return count("SELECT COUNT(*) FROM fds_operator_access_events WHERE user_id=?", account);
    }

    private int detections(Account account) {
        return count("SELECT COUNT(*) FROM fds_operator_access_detections WHERE user_id=?", account);
    }

    private int count(String sql, Account account) {
        return jdbc.queryForObject(sql, Integer.class, account.id());
    }

    private String lastActionStatus(Account account) {
        var statuses = jdbc.queryForList(
                "SELECT action_status FROM fds_operator_access_detections WHERE user_id=? ORDER BY operator_access_detection_id DESC LIMIT 1",
                String.class,
                account.id());
        return statuses.isEmpty() ? null : statuses.getFirst();
    }

    private int incompletePublications(Account account) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE EVENT_TYPE=? AND COMPLETION_DATE IS NULL AND SERIALIZED_EVENT LIKE ?",
                Integer.class,
                OperatorAccessDenied.class.getName(),
                publicationPattern(account));
    }

    private int publications(Account account) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE EVENT_TYPE=? AND SERIALIZED_EVENT LIKE ?",
                Integer.class,
                OperatorAccessDenied.class.getName(),
                publicationPattern(account));
    }

    private static String publicationPattern(Account account) {
        return "%\"userId\":" + account.id() + ",%";
    }
}
