package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.identity.api.StaffSummary;
import com.donworry.identity.dto.MeResponse;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.security.SessionUser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentStaffServiceTests {
    private final CurrentStaffService service = new CurrentStaffService();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // 모든 역할의 공개 정보는 /me와 같으며 가맹점 범위와 기능 권한을 구분한다
    @ParameterizedTest
    @EnumSource(UserRole.class)
    void findCurrent_whenAuthenticated_returnsSamePermissionsAsMe(UserRole role) {
        var user = SessionUser.builder()
                .userId(7L)
                .role(role)
                .merchantId(role == UserRole.OPERATOR ? 10L : null)
                .permissions(List.of("FDS_VIEW"))
                .build();
        authenticate(user);
        var result = service.findCurrent().orElseThrow();
        var me = MeResponse.from(user);
        assertThat(result.userId()).isEqualTo(7L);
        assertThat(result.role()).isEqualTo(role.name());
        assertThat(result.merchantId()).isEqualTo(user.merchantId());
        assertThat(result.permissions()).isEqualTo(me.permissions());
        assertThat(result.permissions())
                .containsExactlyElementsOf(
                        role == UserRole.ADMIN ? List.of("FDS_VIEW", "FINANCE_VIEW") : List.of("FDS_VIEW"));
        assertThat(me.tenantScope()).isEqualTo(role == UserRole.OPERATOR ? "MERCHANT" : "ALL");
    }

    // 비로그인·익명·인증 미완료 상태는 로그인 사용자가 아니다
    @Test
    void findCurrent_whenUnauthenticated_returnsEmpty() {
        assertThat(service.findCurrent()).isEmpty();
        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThat(service.findCurrent()).isEmpty();
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.unauthenticated("user", "password"));
        assertThat(service.findCurrent()).isEmpty();
    }

    // 인증된 잘못된 principal은 비로그인으로 숨기지 않는다
    @Test
    void findCurrent_whenPrincipalUnexpected_throwsException() {
        authenticate("unexpected");
        assertThatIllegalStateException().isThrownBy(service::findCurrent);
    }

    // 이전 세션의 null 권한은 빈 목록이며 반환 목록은 원본과 분리해 변경을 막는다
    @Test
    void findCurrent_whenPermissionsMissingOrMutable_returnsImmutableSnapshot() {
        var user = SessionUser.builder().userId(1L).role(UserRole.OPERATOR).build();
        authenticate(user);
        assertThat(service.findCurrent().orElseThrow().permissions()).isEmpty();
        var permissions = new ArrayList<>(List.of("FDS_VIEW"));
        authenticate(user.toBuilder().permissions(permissions).build());
        var result = service.findCurrent().orElseThrow();
        permissions.clear();
        assertThat(result.permissions()).containsExactly("FDS_VIEW");
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> result.permissions().add("FINANCE_VIEW"));
        assertThat(MeResponse.from(user).tenantScope()).isEqualTo("MERCHANT");
    }

    // 다른 모듈이 권한 없이 만든 공개 record도 빈 목록이다
    @Test
    void staffSummary_whenPermissionsOmitted_returnsEmptyList() {
        var summary = StaffSummary.builder().userId(1L).role("SECURITY_STAFF").build();
        assertThat(summary.permissions()).isEmpty();
    }

    // 관리자에 잘못된 가맹점 값이 있어도 범위는 역할 기준이다
    @Test
    void me_whenAdminHasMerchant_returnsAllScope() {
        var user = SessionUser.builder().role(UserRole.ADMIN).merchantId(10L).build();
        assertThat(MeResponse.from(user).tenantScope()).isEqualTo("ALL");
    }

    private void authenticate(Object principal) {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }
}
