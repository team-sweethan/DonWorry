package com.donworry.fds.listener;

import com.donworry.fds.service.OperatorAccessDetectionService;
import com.donworry.identity.api.OperatorAccessDenied;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

@Component
@RequiredArgsConstructor
public class OperatorAccessDeniedListener {
    private final OperatorAccessDetectionService service;

    // 기본 REQUIRES_NEW는 계정 잠금이 풀린 뒤 커밋해 동시 수신의 집계가 어긋난다. 커밋은 잠금 안의 서비스 트랜잭션이 맡는다.
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(OperatorAccessDenied event) {
        service.receive(event);
    }
}
