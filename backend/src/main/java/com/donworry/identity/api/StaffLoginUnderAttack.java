package com.donworry.identity.api;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

// 로그인 공격(전체 실패율 경계) 중에 보안 직원 로그인이 성공했다는 알림 (#770, ADR-0082). 커밋 뒤 전달된다
// 받는 쪽(P2 SEC-INS-10)은 loginReference로 중복을 거른다. 브라우저 이름은 StaffLoginReader.findLogin으로 읽는다
// attemptCount · failureCount는 판단에 쓴 최근 구간 전체 로그인 시도 · 실패 수(계정 구분 없음)
@Builder
public record StaffLoginUnderAttack(
        Long staffId, UUID loginReference, Instant loggedInAt, long attemptCount, long failureCount) {}
