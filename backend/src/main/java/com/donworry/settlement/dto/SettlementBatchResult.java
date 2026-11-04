package com.donworry.settlement.dto;

import java.time.Instant;
import lombok.Builder;

/** scanned/completed/skipped/failed는 정산 건수가 아닌 가맹점 지급 처리 수이다. */
@Builder
public record SettlementBatchResult(Instant cutoff, long scanned, long completed, long skipped, long failed) {}
