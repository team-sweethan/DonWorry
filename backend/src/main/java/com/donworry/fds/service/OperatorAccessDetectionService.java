package com.donworry.fds.service;

import com.donworry.identity.api.OperatorAccessDenied;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class OperatorAccessDetectionService {
    private final OperatorAccessDetectionTransactionService transactions;
    private final ConcurrentHashMap<Long, Object> userLocks = new ConcurrentHashMap<>();

    public OperatorAccessDetectionService(OperatorAccessDetectionTransactionService transactions) {
        this.transactions = transactions;
    }

    /** 같은 계정의 수신은 트랜잭션 커밋까지 직렬화해 임계값 탐지를 놓치지 않는다. */
    public void receive(OperatorAccessDenied input) {
        var userLock = userLocks.computeIfAbsent(input.userId(), ignored -> new Object());
        synchronized (userLock) {
            transactions.receive(input);
        }
    }
}
