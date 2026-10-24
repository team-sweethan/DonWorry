package com.donworry.identity.service;

import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

@Service
public class AccountLockService {
    // 없는 아이디로 로그인해도 잠금 객체가 계속 늘어나지 않는다. 단일 서버 메모리 세션용이다.
    private final Object[] locks =
            IntStream.range(0, 256).mapToObj(i -> new Object()).toArray();

    public <T> T withAccount(String loginId, Supplier<T> action) {
        String normalized = loginId.trim().toLowerCase(Locale.ROOT);
        synchronized (locks[Math.floorMod(normalized.hashCode(), locks.length)]) {
            return action.get();
        }
    }
}
