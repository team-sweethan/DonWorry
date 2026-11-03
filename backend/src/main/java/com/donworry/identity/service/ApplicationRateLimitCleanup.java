package com.donworry.identity.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "donworry.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ApplicationRateLimitCleanup {
    private final ApplicationRateLimitService limits;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void clean() {
        limits.deleteExpired();
    }
}
