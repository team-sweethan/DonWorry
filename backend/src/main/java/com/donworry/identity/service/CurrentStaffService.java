package com.donworry.identity.service;

import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffSummary;
import com.donworry.identity.security.SessionUser;
import com.donworry.identity.security.SessionUserMapper;
import java.util.Optional;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CurrentStaffService implements CurrentStaff {
    @Override
    public Optional<StaffSummary> findCurrent() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        if (!(authentication.getPrincipal() instanceof SessionUser user)) {
            throw new IllegalStateException("인증 principal이 SessionUser가 아닙니다.");
        }
        return Optional.of(SessionUserMapper.toSummary(user));
    }
}
