package com.donworry.identity.api;

import java.util.List;

// 보안 직원 계정 보안 정지 · 해제 창구 (ADR-0082 5절 · ADR-0084)
// 정지 상태는 SUSPENDED이고 [계정 관리]의 상태 버튼으로는 풀 수 없다. 판정(release)으로만 풀린다
// null · 0 이하 ID는 IllegalArgumentException, 없는 계정 · 역할 불일치는 StaffAccountLockException이다
public interface StaffAccountLocker {

    // 시스템 자동 정지(예: 경보 중 내보내기 시도). SECURITY_STAFF만. 열린 정지가 이미 있으면 그대로 성공한다
    // 호출 쪽 트랜잭션과 따로 커밋하고, 돌아가기 전에 세션을 모두 끊는다 — 호출 쪽이 롤백돼도 정지는 남는다
    void lockBySystem(Long staffId, StaffLockReason reason, Long sourceAlertId);

    // 관리자 정지(예: 판정 중 정지). adminId는 ADMIN이어야 한다. 나머지는 lockBySystem과 같다
    void lockByAdmin(Long staffId, Long adminId, StaffLockReason reason, Long sourceAlertId);

    // 판정으로 해제 — 호출 쪽 트랜잭션에 참여해 판정 저장과 함께 커밋 · 롤백된다
    // 열린 정지가 없으면 아무것도 바꾸지 않는다. 계정이 SUSPENDED일 때만 outcome대로 상태를 바꾼다
    void release(StaffLockRelease release);

    boolean isLocked(Long staffId);

    // 최근 정지부터
    List<StaffLockRecord> findLocks(Long staffId);
}
