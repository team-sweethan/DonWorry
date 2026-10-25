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
        synchronized (locks[indexOf(loginId)]) {
            return action.get();
        }
    }

    public <T> T withAccounts(String first, String second, Supplier<T> action) {
        int a = indexOf(first);
        int b = indexOf(second);
        // 수행자와 대상이 서로 반대인 요청도 항상 같은 순서로 잠근다.
        synchronized (locks[Math.min(a, b)]) {
            synchronized (locks[Math.max(a, b)]) {
                return action.get();
            }
        }
    }

    private int indexOf(String loginId) {
        String normalized = loginId.trim().toLowerCase(Locale.ROOT);
        return Math.floorMod(normalized.hashCode(), locks.length);
    }
}
