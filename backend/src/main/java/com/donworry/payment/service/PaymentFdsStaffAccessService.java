package com.donworry.payment.service;

import com.donworry.identity.api.CurrentStaff;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentFdsStaffAccessService {
    private final CurrentStaff currentStaff;

    public long requireStaff() {
        var staff = currentStaff
                .findCurrent()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("로그인이 필요합니다."));
        if (!"SECURITY_STAFF".equals(staff.role())) throw new AccessDeniedException("권한이 없습니다.");
        if (staff.userId() == null || staff.userId() <= 0) throw new IllegalStateException("직원 식별자가 유효하지 않습니다.");
        return staff.userId();
    }
}
