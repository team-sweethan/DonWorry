package com.donworry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

// 빈 MariaDB에서 앱을 기동해 migration 전체 적용 + JPA validate를 확인한다
// 컨텍스트가 뜨는 것 자체가 1차 검사: SQL 오류·버전 충돌·Entity 불일치면 여기서 실패
// 웹 환경은 기본값(MOCK) — NONE이면 SecurityConfig가 받는 HttpSecurity Bean이 없어 기동 실패
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class FlywayMigrationTests {

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 모든_migration이_성공으로_적용되고_대기중인_것이_없다() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).isNotEmpty();
        assertThat(Arrays.stream(applied).map(MigrationInfo::getState))
                .as("실패·누락된 migration이 없어야 한다")
                .allMatch(state -> state == MigrationState.SUCCESS || state == MigrationState.OUT_OF_ORDER);
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void 적용된_migration과_파일의_체크섬이_일치한다() {
        // 병합된 파일을 고치면 여기서 잡힌다 (CI의 git diff 검사와 이중 방어)
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void Modulith_이벤트_테이블은_Flyway가_대문자_이름으로_만든다() {
        // Modulith SQL이 EVENT_PUBLICATION을 대문자로 조회하므로 이름이 정확히 같아야 한다
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND BINARY table_name = 'EVENT_PUBLICATION'
                """, Integer.class);

        assertThat(count).isOne();
    }
}
