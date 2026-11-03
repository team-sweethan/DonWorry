package com.donworry.investigation.service;

import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 판정 · Case 조회처럼 보안 직원만 할 수 있는 동작의 로그인 사용자 확인 (fds 정본 02 §2.8 · §2.12)
// 전체 API 접근 제어(P1 EPIC #68)가 생겨도 판정 주체는 여기서 한 번 더 확인한다
@Component
@RequiredArgsConstructor
public class SecurityStaffGuard {

    // identity.api가 내보내는 역할 이름 (ADR-0020)
    private static final String SECURITY_STAFF = "SECURITY_STAFF";

    private final CurrentStaff currentStaff;

    // ADMIN도 막는다 — 판정 주체와 Case 열람자는 보안 직원이다. 열어야 하면 이 조건만 바꾼다
    public Long requireSecurityStaffId() {
        StaffSummary staff = currentStaff
                .findCurrent()
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.LOGIN_REQUIRED));
        if (!SECURITY_STAFF.equals(staff.role())) {
            throw new InvestigationException(InvestigationErrorCode.SECURITY_STAFF_ONLY);
        }
        return staff.userId();
    }
}
