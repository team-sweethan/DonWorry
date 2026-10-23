package com.donworry.identity.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.entity.PlatformUser;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.entity.UserStatus;
import java.time.LocalDateTime;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class PlatformUserRepositoryTests {

    @Autowired
    PlatformUserRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    Flyway flyway;

    // 빈 DB에 적용된 시드의 계정 정보와 UTC 생성 시각을 조회한다
    @Test
    void findByLoginId_whenAdminSeeded_returnsActiveAdmin() {
        PlatformUser user = repository.findByLoginId("admin").orElseThrow();

        assertThat(countByRole("ADMIN")).isOne();
        assertThat(user.getUserId()).isPositive();
        assertThat(user.getLoginId()).isEqualTo("admin");
        assertThat(user.getDisplayName()).isEqualTo("demo_shop");
        assertThat(user.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getMerchantId()).isNull();
        assertThat(user.getPasswordHash()).matches("\\$2a\\$10\\$[./A-Za-z0-9]{53}");

        LocalDateTime utcNow = jdbcTemplate.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
        assertThat(user.getCreatedAt()).isBetween(utcNow.minusMinutes(5), utcNow);

        Long permissionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_permissions WHERE user_id = ?", Long.class, user.getUserId());
        assertThat(permissionCount).isZero();
    }

    // 시연용 사장님은 DEMOSHOP 소속에 FDS·자금 권한을 갖고, 보안 직원은 소속·추가 권한이 없다
    @Test
    void findByLoginId_whenDemoUsersSeeded_returnsOperatorAndStaff() {
        PlatformUser boss = repository.findByLoginId("boss").orElseThrow();
        PlatformUser staff = repository.findByLoginId("staff_test").orElseThrow();

        Long demoShopId = jdbcTemplate.queryForObject(
                "SELECT merchant_id FROM merchants WHERE merchant_code = 'DEMOSHOP'", Long.class);
        assertThat(boss.getRole()).isEqualTo(UserRole.OPERATOR);
        assertThat(boss.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(boss.getMerchantId()).isEqualTo(demoShopId);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT permission_code FROM user_permissions WHERE user_id = ?",
                        String.class,
                        boss.getUserId()))
                .containsExactlyInAnyOrder("FDS_VIEW", "FINANCE_VIEW");

        assertThat(staff.getRole()).isEqualTo(UserRole.SECURITY_STAFF);
        assertThat(staff.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(staff.getMerchantId()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM user_permissions WHERE user_id = ?", Long.class, staff.getUserId()))
                .isZero();
    }

    // 없는 로그인 ID는 빈 결과로 반환한다
    @Test
    void findByLoginId_whenUserMissing_returnsEmpty() {
        assertThat(repository.findByLoginId("missing-user")).isEmpty();
    }

    // 재기동처럼 Flyway를 다시 실행해도 계정과 비밀번호 해시는 유지된다
    @Test
    void migrations_whenRunAgain_preserveSingleAdmin() {
        PlatformUser before = repository.findByLoginId("admin").orElseThrow();

        assertThat(flyway.migrate().migrationsExecuted).isZero();

        PlatformUser after = repository.findByLoginId("admin").orElseThrow();
        assertThat(countByRole("ADMIN")).isOne();
        assertThat(after.getUserId()).isEqualTo(before.getUserId());
        assertThat(after.getPasswordHash()).isEqualTo(before.getPasswordHash());
        assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
    }

    private Long countByRole(String role) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM platform_users WHERE role = ?", Long.class, role);
    }
}
