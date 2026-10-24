package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.api.TenantReader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
@Transactional
class TenantReaderMariaDbTests {
    @Autowired
    TenantReader reader;

    @Autowired
    JdbcTemplate jdbc;

    // 실제 DB에서 ACTIVE와 INACTIVE를 구분해 이름과 ID를 반환한다
    @Test
    void findById_whenMerchantExists_returnsStatusAndName() {
        for (String status : new String[] {"ACTIVE", "INACTIVE"}) {
            String code = "reader_test_" + status;
            jdbc.update("""
                    INSERT INTO merchants (merchant_code, display_name, status, pg_fee_rate, created_at)
                    VALUES (?, ?, ?, 0.020000, UTC_TIMESTAMP(6))
                    """, code, "조회 테스트", status);
            Long id =
                    jdbc.queryForObject("SELECT merchant_id FROM merchants WHERE merchant_code = ?", Long.class, code);
            var result = reader.findById(id).orElseThrow();
            assertThat(result.merchantId()).isEqualTo(id);
            assertThat(result.name()).isEqualTo("조회 테스트");
            assertThat(result.status()).isEqualTo(status);
        }
    }

    // 존재하지 않는 가맹점은 빈 결과다
    @Test
    void findById_whenMerchantMissing_returnsEmpty() {
        assertThat(reader.findById(Long.MAX_VALUE)).isEmpty();
    }
}
