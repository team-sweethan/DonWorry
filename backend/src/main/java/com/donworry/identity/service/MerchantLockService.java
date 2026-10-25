package com.donworry.identity.service;

import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

@Service
public class MerchantLockService {
    private final Object[] locks =
            IntStream.range(0, 256).mapToObj(i -> new Object()).toArray();

    public <T> T withMerchant(Long merchantId, Supplier<T> action) {
        if (merchantId == null) return action.get();
        // 계정 잠금보다 먼저 획득하고 커밋·세션 종료까지 유지한다. 단일 서버 메모리 세션용이다.
        synchronized (locks[Math.floorMod(merchantId.hashCode(), locks.length)]) {
            return action.get();
        }
    }
}
