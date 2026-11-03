package com.donworry.settlement.dto;

import java.time.Instant;
import lombok.Builder;

@Builder
public record SettlementBatchResult(Instant cutoff, long scanned, long completed, long skipped, long failed) {}
