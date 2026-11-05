package com.donworry.fds.listener;

import com.donworry.fds.service.OperatorAccessDetectionService;
import com.donworry.identity.api.OperatorAccessDenied;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OperatorAccessDeniedListener {
    private final OperatorAccessDetectionService service;

    @ApplicationModuleListener
    public void on(OperatorAccessDenied event) {
        service.receive(event);
    }
}
