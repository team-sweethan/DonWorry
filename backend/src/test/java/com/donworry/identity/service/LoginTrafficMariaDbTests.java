package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.api.LoginTrafficMinute;
import com.donworry.identity.api.LoginTrafficReader;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// 로그인 보안 대시보드 1분 막대 (#770) — 분 단위 native SQL을 실제 DB로 본다
// 공용 DB에 다른 테스트의 최근 로그인이 섞이지 않게 먼 과거 시각에 넣는다. 끝나면 롤백된다
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
@Transactional
class LoginTrafficMariaDbTests {
    @Autowired
    LoginTrafficReader reader;

    @Autowired
    JdbcTemplate jdbc;

    // 분 경계 · 로그아웃 제외 · 없는 아이디 실패를 잘못 세면 대시보드 막대와 경계 판단 숫자가 어긋난다
    @Test
    void countPerMinute_countsAttemptsFailuresAndUnknownIds() {
        jdbc.update("""
                INSERT INTO platform_users(login_id,password_hash,display_name,status,role,merchant_id,created_at)
                VALUES ('t770traffic','x','집계 테스트','ACTIVE','SECURITY_STAFF',NULL,UTC_TIMESTAMP(6))
                """);
        Long userId =
                jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id='t770traffic'", Long.class);
        String reference = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO login_histories(user_id,event_type,occurred_at,login_reference) VALUES (?,'LOGIN_SUCCESS','2001-02-03 04:05:10',?)",
                userId,
                reference);
        jdbc.update(
                "INSERT INTO login_histories(user_id,event_type,occurred_at) VALUES (?,'LOGIN_FAILURE','2001-02-03 04:05:20')",
                userId);
        failure("2001-02-03 04:05:30");
        failure("2001-02-03 04:05:59.999999");
        failure("2001-02-03 04:06:00");
        jdbc.update(
                "INSERT INTO login_histories(user_id,event_type,occurred_at,login_reference,logout_scope) VALUES (?,'LOGOUT','2001-02-03 04:06:10',?,'CURRENT')",
                userId,
                reference);
        // 끝 시각은 들어가지 않는다 [from, to)
        failure("2001-02-03 04:08:00");

        var minutes =
                reader.countPerMinute(Instant.parse("2001-02-03T04:05:00Z"), Instant.parse("2001-02-03T04:08:00Z"));

        assertThat(minutes)
                .containsExactly(
                        LoginTrafficMinute.builder()
                                .minuteStart(Instant.parse("2001-02-03T04:05:00Z"))
                                .attempts(4)
                                .failures(3)
                                .unknownAccountFailures(2)
                                .build(),
                        LoginTrafficMinute.builder()
                                .minuteStart(Instant.parse("2001-02-03T04:06:00Z"))
                                .attempts(1)
                                .failures(1)
                                .unknownAccountFailures(1)
                                .build());
    }

    private void failure(String at) {
        jdbc.update("INSERT INTO login_histories(user_id,event_type,occurred_at) VALUES (NULL,'LOGIN_FAILURE',?)", at);
    }
}
