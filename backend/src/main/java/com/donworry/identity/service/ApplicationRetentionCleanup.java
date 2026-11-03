package com.donworry.identity.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "donworry.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ApplicationRetentionCleanup {
    private final ApplicationRetentionService retention;

    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    public void clean() {
        retention.clean();
    }
}
