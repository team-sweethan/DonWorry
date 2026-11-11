package com.donworry.investigation.dto;

import java.time.Instant;
import lombok.Builder;

// 꺾은선 한 점 (#770) — start부터 bucketSeconds 동안의 시도 · 실패. 시도가 없던 칸도 0으로 채운다
@Builder
public record LoginSecurityBucketResponse(Instant start, long attempts, long failures, long unknownAccountFailures) {}
