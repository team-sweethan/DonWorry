package com.donworry.identity.service;

import java.time.Instant;
import java.util.UUID;

// 유효한 서명 확인 후 한 번만 소비하며 업무 트랜잭션의 롤백으로 되돌리지 않는다.
public interface MerchantNonceStore {
    boolean consume(Long merchantId, UUID nonce, Instant expiresAt, long keyVersion);
}
