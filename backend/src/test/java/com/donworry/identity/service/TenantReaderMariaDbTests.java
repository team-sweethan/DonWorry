package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.api.TenantReader;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
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

    @Autowired
    EntityManager entityManager;

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
            assertThat(result.pgFeeRate()).isEqualByComparingTo("0.020000");
        }
    }

    // 가맹점별 0과 소수 6자리 수수료율을 고정값 대체나 반올림 없이 반환한다
    @Test
    void findById_whenMerchantRatesDiffer_preservesStoredRates() {
        for (String status : new String[] {"ACTIVE", "INACTIVE"}) {
            for (String rate : new String[] {"0.000000", "0.012345", "0.999999"}) {
                String code = "fee_reader_" + status + rate;
                jdbc.update("""
                        INSERT INTO merchants (merchant_code, display_name, status, pg_fee_rate, created_at)
                        VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6))
                        """, code, "수수료율 조회 테스트", status, new BigDecimal(rate));
                Long id = jdbc.queryForObject(
                        "SELECT merchant_id FROM merchants WHERE merchant_code = ?", Long.class, code);
                assertThat(reader.findById(id).orElseThrow().pgFeeRate()).isEqualByComparingTo(rate);
            }
        }
    }

    // 별도 영속성 문맥에서 다시 조회하면 DB의 변경된 수수료율을 반환한다
    @Test
    void findById_whenStoredRateChanges_returnsUpdatedRate() {
        String code = "fee_reader_updated";
        jdbc.update("""
                INSERT INTO merchants (merchant_code, display_name, status, pg_fee_rate, created_at)
                VALUES (?, ?, 'ACTIVE', 0.020000, UTC_TIMESTAMP(6))
                """, code, "변경 조회 테스트");
        Long id = jdbc.queryForObject("SELECT merchant_id FROM merchants WHERE merchant_code = ?", Long.class, code);
        assertThat(reader.findById(id).orElseThrow().pgFeeRate()).isEqualByComparingTo("0.020000");
        jdbc.update("UPDATE merchants SET pg_fee_rate = 0.031234 WHERE merchant_id = ?", id);
        entityManager.clear();
        assertThat(reader.findById(id).orElseThrow().pgFeeRate()).isEqualByComparingTo("0.031234");
    }

    // 존재하지 않는 가맹점은 빈 결과다
    @Test
    void findById_whenMerchantMissing_returnsEmpty() {
        assertThat(reader.findById(Long.MAX_VALUE)).isEmpty();
    }
}
