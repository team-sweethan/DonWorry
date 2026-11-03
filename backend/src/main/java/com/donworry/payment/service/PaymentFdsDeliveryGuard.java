package com.donworry.payment.service;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

// 단일 앱 인스턴스의 전달 복구 전용 잠금이다. 리스너·재평가에서는 사용하지 않는다.
@Service
public class PaymentFdsDeliveryGuard {
    private final ReentrantLock lock = new ReentrantLock();

    // 상태 조회부터 재보고·재전달 요청·최종 조회까지 이 콜백 안에서 수행한다.
    // 잠금 밖에서 읽어 둔 publication을 전달하면 조회/실행 경합을 막을 수 없다.
    <T> T execute(Supplier<T> recovery) {
        Objects.requireNonNull(recovery, "recovery");
        if (lock.isHeldByCurrentThread() || !lock.tryLock()) throw new PaymentFdsDeliveryBusyException();
        try {
            return recovery.get();
        } finally {
            lock.unlock();
        }
    }
}
