package com.donworry.identity.service;

import java.util.Base64;
import org.springframework.jdbc.core.JdbcTemplate;
// 기존 업무 HTTP 테스트의 키 조회 대역과 nonce 버전 검사가 같은 현재 키를 보게 한다.

public final class MerchantIntegrationTestFixtures {
    private MerchantIntegrationTestFixtures() {}

    public static void installKey(JdbcTemplate jdbc, Long merchantId, byte[] secret) {
        var cipher = new MerchantKeyCipher("TEST", name -> Base64.getEncoder().encodeToString(new byte[32]));
        byte[] nonce = cipher.newNonce(), encrypted = cipher.encrypt(merchantId, 1, secret, nonce);
        jdbc.update(
                "INSERT INTO merchant_integrations(merchant_id,status,encrypted_secret,encryption_nonce,master_key_id,key_version,key_issued_at,connected_at,updated_at) VALUES(?,'CONNECTED',?,?,'TEST',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                merchantId,
                encrypted,
                nonce);
    }
}
