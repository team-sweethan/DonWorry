package com.donworry.identity.api;

import java.util.List;

// 보안 직원(SECURITY_STAFF) 목록 — investigation의 FDS 사건 자동 배정 · 담당 이름 표시용 (#608, ADR-0082 2절)
public interface SecurityStaffDirectory {
    // 정지(INACTIVE) 직원도 active=false로 넣는다 — 지난 사건의 담당 이름을 보여 줘야 해서. 배정 후보는 active만 쓴다
    // 직원 ID 오름차순
    List<SecurityStaffEntry> securityStaff();
}
