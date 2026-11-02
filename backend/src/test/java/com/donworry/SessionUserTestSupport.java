package com.donworry;

import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffSummary;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.security.SessionUser;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// 서비스 직접 호출용 CurrentStaff 대역과 HTTP 필터의 principal이 같은 사용자를 보게 한다.
public final class SessionUserTestSupport {
    private SessionUserTestSupport() {}

    public static MockHttpServletRequestBuilder asCurrentStaff(
            MockHttpServletRequestBuilder builder, CurrentStaff currentStaff) {
        currentStaff.findCurrent().ifPresent(staff -> builder.with(sessionUser(staff)));
        return builder;
    }

    public static RequestPostProcessor sessionUser(StaffSummary staff) {
        var user = SessionUser.builder()
                .userId(staff.userId())
                .loginId("case-test-" + staff.userId())
                .displayName(staff.displayName())
                .role(UserRole.valueOf(staff.role()))
                .merchantId(staff.merchantId())
                .permissions(staff.permissions())
                .build();
        return SecurityMockMvcRequestPostProcessors.authentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
    }
}
