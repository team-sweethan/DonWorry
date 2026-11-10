package com.donworry.fds.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.donworry.HttpCsrfTestSupport;
import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.InsiderAlertRaised;
import com.donworry.identity.api.StaffAccessRecorded;
import com.donworry.identity.api.StaffLoginUnderAttack;
import com.donworry.identity.service.UserSessionService;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.json.JsonMapper;

// #770 크리덴셜 스터핑 — 실제 로그인 HTTP부터 P1 실패율 알림, P2 SEC-INS-10 높음, 바로 정지 · 세션 끊김까지 (#790)
// 실패율은 전체 계정 기준이라 넣은 실패 줄을 테스트가 끝나면 지운다. 남기면 뒤 테스트의 보안 직원 로그인이 걸린다 (mariaDbTest는 차례로 돈다)
@Tag("mariadb")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoginUnderAttackFlowMariaDbTests {
    private static final String PASSWORD = "AttackFlowPass123";
    private static final Duration WAIT = Duration.ofSeconds(15);
    // 평소 쓰던 브라우저 — UserAgentDevice가 "Edge · Windows"로 읽는다. 테스트 HttpClient는 다른 브라우저로 읽힌다
    private static final String EDGE_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0";

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

    private String prefix;
    private long staffId;

    @BeforeEach
    void setUp() {
        prefix = "t770" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update(
                "INSERT INTO platform_users(login_id,password_hash,display_name,status,role,merchant_id,created_at)"
                        + " VALUES (?,?,'공격 흐름 테스트','ACTIVE','SECURITY_STAFF',NULL,UTC_TIMESTAMP(6))",
                prefix + "staff",
                encoder.encode(PASSWORD));
        staffId = jdbc.queryForObject(
                "SELECT user_id FROM platform_users WHERE login_id=?", Long.class, prefix + "staff");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM login_histories WHERE user_agent=?", prefix);
        sessions.expireAll(staffId);
        for (Class<?> type :
                List.of(StaffLoginUnderAttack.class, StaffAccessRecorded.class, InsiderAlertRaised.class)) {
            jdbc.update(
                    "DELETE FROM EVENT_PUBLICATION WHERE EVENT_TYPE=? AND JSON_VALUE(SERIALIZED_EVENT,'$.staffId')=?",
                    type.getName(),
                    staffId);
        }
        jdbc.update("DELETE FROM staff_access_events WHERE staff_id=?", staffId);
        jdbc.update(
                "DELETE e FROM insider_alert_evidences e JOIN insider_alerts a ON a.alert_id = e.alert_id"
                        + " WHERE a.staff_id=?",
                staffId);
        jdbc.update("DELETE FROM insider_alerts WHERE staff_id=?", staffId);
        jdbc.update("DELETE FROM insider_detection_states WHERE staff_id=?", staffId);
        jdbc.update("DELETE FROM staff_account_locks WHERE staff_id=?", staffId);
        jdbc.update("DELETE FROM admin_change_histories WHERE target_user_id=?", staffId);
        jdbc.update("DELETE FROM login_histories WHERE user_id=?", staffId);
        jdbc.update("DELETE FROM platform_users WHERE user_id=?", staffId);
    }

    // 알림 → 경보 → 정지 사슬이 끊기면 공격자가 뚫은 계정으로 고객정보를 보고 거래 내역을 내보낸다
    // 탈취 의심이면 내보내기를 기다리지 않고 바로 정지 · 세션 끊김이라, 내보내기는 차단(403) 전에 로그인 끊김(401)이다 (#790)
    @Test
    void loginUnderAttackFlow_suspendsAndCutsSessionBeforeExport() throws Exception {
        jdbc.update(
                "INSERT INTO login_histories(user_id,event_type,occurred_at,client_ip,user_agent,login_reference)"
                        + " VALUES (?,'LOGIN_SUCCESS',UTC_TIMESTAMP(6) - INTERVAL 2 DAY,'10.0.0.1',?,?)",
                staffId,
                EDGE_WINDOWS,
                UUID.randomUUID().toString());
        // 최근 5분 안에 없는 아이디 · 틀린 비밀번호 실패 40건 (기준: 30건 이상 · 실패 30% 이상)
        for (int i = 0; i < 40; i++) {
            jdbc.update(
                    "INSERT INTO login_histories(user_id,event_type,occurred_at,client_ip,user_agent)"
                            + " VALUES (NULL,'LOGIN_FAILURE',UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE,'203.0.113.9',?)",
                    prefix);
        }
        HttpClient attacker =
                HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        var login = HttpCsrfTestSupport.send(
                attacker,
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                mapper.writeValueAsString(Map.of("loginId", prefix + "staff", "password", PASSWORD)))));
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);

        await().atMost(WAIT)
                .until(() -> jdbc.queryForList(
                                "SELECT severity FROM insider_alerts WHERE staff_id=? AND rule_code='INS_10' AND status='OPEN'",
                                String.class,
                                staffId)
                        .contains("HIGH"));

        await().atMost(WAIT)
                .until(() -> "SUSPENDED"
                        .equals(jdbc.queryForObject(
                                "SELECT status FROM platform_users WHERE user_id=?", String.class, staffId)));
        assertThat(jdbc.queryForList(
                        "SELECT reason_code FROM staff_account_locks WHERE staff_id=? AND status='OPEN'",
                        String.class,
                        staffId))
                .containsExactly("LOGIN_TAKEOVER_SUSPECTED");

        var after = attacker.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/payment-transactions"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        // 세션이 끊겼으니 거래 목록 · 내보내기 모두 로그인부터 다시 해야 한다
        assertThat(after.statusCode()).isEqualTo(401);
    }
}
