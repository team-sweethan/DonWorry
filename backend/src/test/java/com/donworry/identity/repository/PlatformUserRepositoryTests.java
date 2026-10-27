package com.donworry.identity.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.identity.entity.PlatformUser;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.entity.UserStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.mariadb.MariaDBContainer;

@Tag("mariadb")
@SpringBootTest
@Import(PlatformUserRepositoryTests.SeedDatabase.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlatformUserRepositoryTests {

    // 빈 DB 시드는 다른 HTTP 검사가 만든 계정 수와 실행 순서에 영향을 받지 않아야 한다.
    @TestConfiguration(proxyBeanMethods = false)
    static class SeedDatabase {
        @Bean
        @ServiceConnection
        MariaDBContainer seedDatabase() {
            return new MariaDBContainer("mariadb:11.8")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");
        }
    }

    @Autowired
    PlatformUserRepository repository;

    @Autowired
    UserPermissionRepository permissionRepository;

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
        assertThat(permissionRepository.findPermissionCodesByUserId(boss.getUserId()))
                .containsExactly("FDS_VIEW", "FINANCE_VIEW");

        assertThat(staff.getRole()).isEqualTo(UserRole.SECURITY_STAFF);
        assertThat(staff.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(staff.getMerchantId()).isNull();
        assertThat(permissionRepository.findPermissionCodesByUserId(staff.getUserId()))
                .isEmpty();
    }

    // CakeShop 시드는 별도 가맹점·키 참조·사장님 권한을 갖고 재적용으로 중복되지 않는다
    @Test
    void migrations_whenCakeShopSeeded_preserveMerchantAndOwner() {
        PlatformUser owner = repository.findByLoginId("cakeshop_owner").orElseThrow();
        var merchant = jdbcTemplate.queryForMap("SELECT * FROM merchants WHERE merchant_code='CAKESHOP'");
        assertThat(merchant.get("display_name")).isEqualTo("CakeShop");
        assertThat(merchant.get("status")).isEqualTo("ACTIVE");
        assertThat((BigDecimal) merchant.get("pg_fee_rate")).isEqualByComparingTo("0.020000");
        assertThat(merchant.get("secret_reference")).isEqualTo("DONWORRY_MERCHANT_HMAC_CAKESHOP");
        assertThat(merchant.get("webhook_url")).isNull();
        assertThat(owner.getMerchantId()).isEqualTo(merchant.get("merchant_id"));
        assertThat(owner.getMerchantId())
                .isNotEqualTo(repository.findByLoginId("boss").orElseThrow().getMerchantId());
        assertThat(owner.getRole()).isEqualTo(UserRole.OPERATOR);
        assertThat(owner.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(owner.getDisplayName()).isEqualTo("CakeShop 사장님");
        assertThat(new BCryptPasswordEncoder().matches("1234", owner.getPasswordHash()))
                .isTrue();
        assertThat(permissionRepository.findPermissionCodesByUserId(owner.getUserId()))
                .containsExactlyInAnyOrder("FDS_VIEW", "FINANCE_VIEW");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(repository.findByLoginId("cakeshop_owner").orElseThrow().getUserId())
                .isEqualTo(owner.getUserId());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM merchants WHERE merchant_code='CAKESHOP'", Long.class))
                .isOne();
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
