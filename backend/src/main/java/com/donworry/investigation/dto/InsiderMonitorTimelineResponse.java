package com.donworry.investigation.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

// 내부자 관제 가운데 타임라인 (#679) — from = 마지막 로그인 성공 30분 전(하루 안에 로그인이 없으면 하루 전), 줄은 시간 오름차순
// truncated = 200줄이 넘어 오래된 줄을 뺐다
@Builder
public record InsiderMonitorTimelineResponse(
        InsiderMonitorStaffResponse staff,
        Instant from,
        Instant to,
        boolean truncated,
        List<InsiderMonitorEventResponse> events) {}
