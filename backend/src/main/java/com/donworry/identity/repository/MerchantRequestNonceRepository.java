package com.donworry.identity.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// 정상적인 재전송 충돌에서 ORM이 SQL 오류·nonce 값을 로그에 쓰지 않도록 JDBC로 기록한다.
@Repository
@RequiredArgsConstructor
public class MerchantRequestNonceRepository {
    private final JdbcTemplate jdbc;

    public boolean lockMerchant(Long merchantId) {
        return !jdbc.queryForList(
                        "SELECT merchant_id FROM merchants WHERE merchant_id=? FOR UPDATE", Long.class, merchantId)
                .isEmpty();
    }

    public boolean containsNonce(Long merchantId, String nonce) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM merchant_request_nonces WHERE merchant_id=? AND nonce=?)",
                Boolean.class,
                merchantId,
                nonce));
    }

    public int insertNonce(Long merchantId, String nonce, String expiresAt) {
        return jdbc.update(
                "INSERT INTO merchant_request_nonces (merchant_id, nonce, expires_at) "
                        + "VALUES (?, ?, CAST(? AS DATETIME(6)))",
                merchantId,
                nonce,
                expiresAt);
    }

    public int deleteExpiredBatch(String cutoff) {
        return jdbc.update(
                "DELETE FROM merchant_request_nonces WHERE expires_at <= CAST(? AS DATETIME(6)) "
                        + "ORDER BY expires_at, request_nonce_id LIMIT 1000",
                cutoff);
    }
}
