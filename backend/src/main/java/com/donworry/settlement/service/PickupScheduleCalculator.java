package com.donworry.settlement.service;

import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class PickupScheduleCalculator {
    public Instant settlementAt(Instant pickupAt) {
        return pickupAt == null ? null : pickupAt.plus(Duration.ofHours(72));
    }
}
