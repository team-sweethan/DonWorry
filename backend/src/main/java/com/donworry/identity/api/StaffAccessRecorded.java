package com.donworry.identity.api;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

// 접근 기록이 저장됐다는 알림. 커밋 뒤 전달된다. eventId는 staff_access_events 행 PK — 받는 쪽은 이 값으로 중복을 거른다
// loginReference는 이 행동이 나온 로그인 번호(login_histories와 같은 값). 세션에 없으면 null
@Builder
public record StaffAccessRecorded(
        Long eventId,
        Long staffId,
        String actorRole,
        StaffAccessType accessType,
        StaffAccessResult result,
        StaffAccessDenyReason denyReason,
        Long caseId,
        Long merchantId,
        Long paymentId,
        String customerRef,
        StaffAccessFieldType fieldType,
        Integer itemCount,
        UUID loginReference,
        Instant occurredAt) {}
