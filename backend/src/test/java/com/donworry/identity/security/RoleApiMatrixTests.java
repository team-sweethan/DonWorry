package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.donworry.identity.entity.UserRole;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

// 직원 세션 체인의 역할 × API 권한표를 한 곳에서 검사한다 (#420, ADR-0067).
// CakeShop HMAC · AI 서버 체인과 로그인 전 공개 경로는 각 전용 테스트가 검사한다.
@WebMvcTest(controllers = SecurityConfigTests.ProbeController.class)
@Import({
    SecurityConfigTests.ProbeController.class,
    SecurityConfig.class,
    JsonAuthenticationEntryPoint.class,
    JsonAccessDeniedHandler.class,
    SecurityProblemResponseWriter.class
})
class RoleApiMatrixTests {

    // 권한 목록이 있는 ADMIN · 보안 직원은 사장님 경로가 권한이 아니라 역할로 막히는지,
    // 권한 목록이 빈 ADMIN · 보안 직원(시드 staff_test와 같음)은 직원 경로가 역할만으로 열리는지 확인한다
    enum Persona {
        ANONYMOUS(null, null, List.of()),
        ADMIN(UserRole.ADMIN, null, List.of("FDS_VIEW", "FINANCE_VIEW")),
        ADMIN_NO_PERMISSION(UserRole.ADMIN, null, List.of()),
        SECURITY_STAFF(UserRole.SECURITY_STAFF, null, List.of("FDS_VIEW", "FINANCE_VIEW")),
        SECURITY_STAFF_NO_PERMISSION(UserRole.SECURITY_STAFF, null, List.of()),
        OWNER_NO_PERMISSION(UserRole.OPERATOR, 1L, List.of()),
        OWNER_FDS_VIEW(UserRole.OPERATOR, 1L, List.of("FDS_VIEW")),
        OWNER_FINANCE_VIEW(UserRole.OPERATOR, 1L, List.of("FINANCE_VIEW")),
        OWNER_WITHOUT_MERCHANT(UserRole.OPERATOR, null, List.of("FDS_VIEW", "FINANCE_VIEW"));

        final UserRole role;
        final Long merchantId;
        final List<String> permissions;

        Persona(UserRole role, Long merchantId, List<String> permissions) {
            this.role = role;
            this.merchantId = merchantId;
            this.permissions = permissions;
        }
    }

    record Route(HttpMethod method, String path, Set<Persona> allowed) {}

    private static final Set<Persona> LOGGED_IN = EnumSet.complementOf(EnumSet.of(Persona.ANONYMOUS));
    private static final Set<Persona> ADMIN = EnumSet.of(Persona.ADMIN, Persona.ADMIN_NO_PERMISSION);
    private static final Set<Persona> STAFF = EnumSet.of(Persona.SECURITY_STAFF, Persona.SECURITY_STAFF_NO_PERMISSION);
    private static final Set<Persona> STAFF_OR_ADMIN = EnumSet.of(
            Persona.SECURITY_STAFF, Persona.SECURITY_STAFF_NO_PERMISSION, Persona.ADMIN, Persona.ADMIN_NO_PERMISSION);
    private static final Set<Persona> OWNER =
            EnumSet.of(Persona.OWNER_NO_PERMISSION, Persona.OWNER_FDS_VIEW, Persona.OWNER_FINANCE_VIEW);
    private static final Set<Persona> OWNER_FDS = EnumSet.of(Persona.OWNER_FDS_VIEW);
    private static final Set<Persona> OWNER_FINANCE = EnumSet.of(Persona.OWNER_FINANCE_VIEW);

    private static Route route(String method, String path, Set<Persona> allowed) {
        return new Route(HttpMethod.valueOf(method), path, allowed);
    }

    private static final List<Route> ROUTES = List.of(
            // 로그인한 본인
            route("GET", "/api/auth/me", LOGGED_IN),
            route("POST", "/api/auth/logout-all", LOGGED_IN),
            route("POST", "/api/auth/password", LOGGED_IN),
            route("GET", "/api/auth/login-histories", LOGGED_IN),
            route("GET", "/api/auth/login-histories/latest-success", LOGGED_IN),
            // 관리자: 계정 · 가맹점 · 이용 신청 심사 · 가맹점 연동 키 관리
            route("GET", "/api/admin/users", ADMIN),
            route("GET", "/api/admin/users/1", ADMIN),
            route("POST", "/api/admin/users", ADMIN),
            route("PUT", "/api/admin/users/1/permissions", ADMIN),
            route("PUT", "/api/admin/users/1/status", ADMIN),
            route("POST", "/api/admin/users/1/password-reset", ADMIN),
            route("GET", "/api/admin/users/1/login-histories", ADMIN),
            route("GET", "/api/admin/merchants/options", ADMIN),
            route("GET", "/api/admin/merchants", ADMIN),
            route("GET", "/api/admin/merchants/1", ADMIN),
            route("POST", "/api/admin/merchants", ADMIN),
            route("PUT", "/api/admin/merchants/1/status", ADMIN),
            route("GET", "/api/admin/merchants/1/integration", ADMIN),
            route("POST", "/api/admin/merchants/1/integration/revoke", ADMIN),
            route("GET", "/api/admin/merchant-applications", ADMIN),
            route("GET", "/api/admin/merchant-applications/1", ADMIN),
            route("POST", "/api/admin/merchant-applications/1/approve", ADMIN),
            route("POST", "/api/admin/merchant-applications/1/reject", ADMIN),
            route("GET", "/api/admin/merchant-applications/1/mail-deliveries", ADMIN),
            route("POST", "/api/admin/merchant-applications/1/mail-resends", ADMIN),
            // 관리자만: FDS 사건 담당 현황 · 변경 · 요청 처리 (#608). 보안 직원은 자기 담당을 바꾸지 못한다
            route("GET", "/api/admin/case-assignments", ADMIN),
            route("GET", "/api/admin/case-assignments/staff/1/cases", ADMIN),
            route("POST", "/api/admin/case-assignments/cases/1/reassign", ADMIN),
            route("POST", "/api/admin/case-assignments/staff/1/handover", ADMIN),
            route("POST", "/api/admin/case-assignments/backfill", ADMIN),
            route("GET", "/api/admin/case-assignments/requests", ADMIN),
            route("GET", "/api/admin/case-assignments/requests/pending-count", ADMIN),
            route("POST", "/api/admin/case-assignments/requests/1/approve", ADMIN),
            route("POST", "/api/admin/case-assignments/requests/1/reject", ADMIN),
            // 관리자만: 주문자 전화 원문 열람 사후 검토 (#619)
            route("GET", "/api/admin/raw-view-reviews", ADMIN),
            route("GET", "/api/admin/raw-view-reviews/pending-count", ADMIN),
            route("POST", "/api/admin/raw-view-reviews/1/review", ADMIN),
            // 관리자만: 내부자 경보 (#516)
            route("GET", "/api/admin/insider-alerts", ADMIN),
            route("GET", "/api/admin/insider-alerts/open-count", ADMIN),
            // AI 조사 긴급정지 (#626, ADR-0083): 조회 · 끄기는 보안 직원 + 관리자, 다시 켜기는 관리자만
            route("GET", "/api/ai-switch", STAFF_OR_ADMIN),
            route("POST", "/api/ai-switch/stop", STAFF_OR_ADMIN),
            route("POST", "/api/ai-switch/resume", ADMIN),
            route("GET", "/api/admin/insider-alerts/1", ADMIN),
            route("POST", "/api/admin/insider-alerts/1/close", ADMIN),
            // 보안 직원 + 관리자: 가맹점 목록 · FDS · 결제 운영 조회. 세부 수정 권한은 FDS 서비스가 다시 확인한다
            route("GET", "/api/merchants", STAFF_OR_ADMIN),
            route("GET", "/api/fds/evaluations", STAFF_OR_ADMIN),
            route("GET", "/api/fds/evaluations/1", STAFF_OR_ADMIN),
            route("GET", "/api/fds/evaluations/rule-catalog", STAFF_OR_ADMIN),
            route("GET", "/api/fds/rule-policies", STAFF_OR_ADMIN),
            route("POST", "/api/fds/rules/R001/disable-requests", STAFF_OR_ADMIN),
            route("PUT", "/api/fds/rules/R001/active", STAFF_OR_ADMIN),
            route("GET", "/api/fds/rule-change-requests", STAFF_OR_ADMIN),
            route("PUT", "/api/fds/rule-change-requests/1/review", STAFF_OR_ADMIN),
            route("GET", "/api/fds/blocklist", STAFF_OR_ADMIN),
            route("GET", "/api/fds/blocklist/status", STAFF_OR_ADMIN),
            route("POST", "/api/fds/blocklist", STAFF_OR_ADMIN),
            route("PUT", "/api/fds/blocklist/1/deactivate", STAFF_OR_ADMIN),
            route("POST", "/api/fds/evaluations/1/blocklist-entries", STAFF_OR_ADMIN),
            route("GET", "/api/payment-operations", STAFF_OR_ADMIN),
            route("GET", "/api/payment-operations/1", STAFF_OR_ADMIN),
            route("GET", "/api/payment-transactions", STAFF_OR_ADMIN),
            route("GET", "/api/payment-transactions/export", STAFF_OR_ADMIN),
            // 보안 직원만: FDS 복구 실행 · Case 조회 · 판정 · 보고서 (ADMIN 제외, ADR-0065)
            route("POST", "/api/payment-operations/1/fds-reevaluations", STAFF),
            route("POST", "/api/payment-operations/1/fds-recovery-deliveries", STAFF),
            route("GET", "/api/security-cases", STAFF),
            route("GET", "/api/security-cases/counts", STAFF),
            route("GET", "/api/security-cases/payment-links", STAFF),
            route("GET", "/api/security-cases/handoffs", STAFF),
            route("GET", "/api/security-cases/1", STAFF),
            route("POST", "/api/security-cases/1/decisions", STAFF),
            route("POST", "/api/security-cases/1/handoffs/2/recheck", STAFF),
            route("POST", "/api/security-cases/1/reports", STAFF),
            route("POST", "/api/security-cases/1/assignment-requests", STAFF),
            route("GET", "/api/security-cases/1/payments/2/contact", STAFF),
            route("POST", "/api/security-cases/1/payments/2/contact-match", STAFF),
            route("POST", "/api/security-cases/1/payments/2/contact-raw-view", STAFF),
            route("GET", "/api/security-cases/1/reports/2/progress", STAFF),
            route("GET", "/api/ml-shadow-scores", STAFF),
            // 사장님 (세션 가맹점): 연동 키는 권한 없이, 보안 조치 결과는 FDS_VIEW, 정산은 FINANCE_VIEW
            route("GET", "/api/owner/integration", OWNER),
            route("POST", "/api/owner/integration/keys", OWNER),
            route("POST", "/api/owner/integration/keys/reveal", OWNER),
            route("POST", "/api/owner/integration/keys/reissue", OWNER),
            route("GET", "/api/merchant/security-cases", OWNER_FDS),
            route("GET", "/api/merchant/security-cases/1", OWNER_FDS),
            route("GET", "/api/v1/settlements/report", OWNER_FINANCE),
            route("GET", "/api/v1/settlements/cash-flow/forecast", OWNER_FINANCE),
            route("GET", "/api/v1/settlements/cash-flow/shortages", OWNER_FINANCE),
            route("GET", "/api/v1/settlements/cash-flow/settings", OWNER_FINANCE),
            route("PUT", "/api/v1/settlements/cash-flow/settings", OWNER_FINANCE),
            route("GET", "/api/v1/settlements/cash-flow/expenses", OWNER_FINANCE),
            route("POST", "/api/v1/settlements/cash-flow/expenses", OWNER_FINANCE),
            route("PUT", "/api/v1/settlements/cash-flow/expenses/1", OWNER_FINANCE),
            route("DELETE", "/api/v1/settlements/cash-flow/expenses/1", OWNER_FINANCE),
            route("PUT", "/api/v1/settlements/cash-flow/expenses/1/completion", OWNER_FINANCE),
            route("GET", "/api/v1/settlements/cash-flow/recurring-expenses", OWNER_FINANCE),
            route("POST", "/api/v1/settlements/cash-flow/recurring-expenses", OWNER_FINANCE),
            route("DELETE", "/api/v1/settlements/cash-flow/recurring-expenses/1", OWNER_FINANCE),
            route("GET", "/api/v1/settlements/cash-flow/recurring-expenses/occurrences", OWNER_FINANCE),
            route(
                    "PUT",
                    "/api/v1/settlements/cash-flow/recurring-expenses/1/occurrences/2026-11-02/completion",
                    OWNER_FINANCE));

    @MockitoBean
    com.donworry.identity.service.OperatorAccessDenialService accessDenials;

    @MockitoBean
    com.donworry.identity.service.LoginHistoryService loginHistories;

    @MockitoBean
    UserDetailsService userDetailsService;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JsonMapper jsonMapper;

    // 권한표의 허용 역할만 필터를 통과하고, 나머지는 비로그인 401 AUTH_001 · 로그인 403 AUTH_002다
    @Test
    void roleApiMatrix_eachRoute_allowsOnlyListedPersonas() throws Exception {
        List<String> mismatches = new ArrayList<>();
        for (Route route : ROUTES) {
            for (Persona persona : Persona.values()) {
                var response =
                        mockMvc.perform(requestAs(persona, route)).andReturn().getResponse();
                int status = response.getStatus();
                boolean allowed = route.allowed().contains(persona);
                String expected;
                boolean ok;
                if (allowed) {
                    expected = "필터 통과(401·403 아님)";
                    ok = status != 401 && status != 403;
                } else if (persona == Persona.ANONYMOUS) {
                    expected = "401 AUTH_001";
                    ok = status == 401 && code(response.getContentAsString()).equals("AUTH_001");
                } else {
                    expected = "403 AUTH_002";
                    ok = status == 403 && code(response.getContentAsString()).equals("AUTH_002");
                }
                if (!ok) {
                    mismatches.add("%s %s / %s: 기대 %s, 실제 %d"
                            .formatted(route.method(), route.path(), persona, expected, status));
                }
            }
        }
        assertThat(mismatches).isEmpty();
    }

    // 예전 접두사(/**) 규칙 아래라도 등록하지 않은 경로·메서드는 허용 역할에게도 열리지 않는다
    @Test
    void roleApiMatrix_unregisteredRouteUnderKnownPrefix_isDenied() throws Exception {
        var unregistered = List.of(
                new Route(HttpMethod.GET, "/api/admin/new-feature", ADMIN),
                new Route(HttpMethod.DELETE, "/api/admin/users/1", ADMIN),
                new Route(HttpMethod.GET, "/api/v1/settlements/new-feature", OWNER_FINANCE),
                new Route(HttpMethod.PATCH, "/api/v1/settlements/report", OWNER_FINANCE),
                new Route(HttpMethod.GET, "/api/fds/rules/R001/new-feature", STAFF_OR_ADMIN),
                new Route(HttpMethod.DELETE, "/api/fds/blocklist/1", STAFF_OR_ADMIN),
                new Route(HttpMethod.GET, "/api/fds/evaluations/1/new-feature", STAFF_OR_ADMIN),
                new Route(HttpMethod.POST, "/api/owner/integration/new-feature", OWNER),
                new Route(HttpMethod.GET, "/api/auth/login-histories/new-feature", LOGGED_IN));
        List<String> opened = new ArrayList<>();
        for (Route route : unregistered) {
            for (Persona persona : route.allowed()) {
                var response =
                        mockMvc.perform(requestAs(persona, route)).andReturn().getResponse();
                if (response.getStatus() != 403
                        || !code(response.getContentAsString()).equals("AUTH_002")) {
                    opened.add(
                            "%s %s / %s: 실제 %d".formatted(route.method(), route.path(), persona, response.getStatus()));
                }
            }
        }
        assertThat(opened).isEmpty();
    }

    private MockHttpServletRequestBuilder requestAs(Persona persona, Route route) {
        // 변경 요청도 CSRF가 아니라 역할 규칙을 검사하도록 유효 토큰을 붙인다
        var builder = request(route.method(), route.path()).with(request -> {
            request.setCookies(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "matrix-test-token"));
            request.addHeader("X-XSRF-TOKEN", "matrix-test-token");
            return request;
        });
        if (persona == Persona.ANONYMOUS) return builder;
        var user = SessionUser.builder()
                .userId((long) (persona.ordinal() + 1))
                .loginId("matrix-" + persona.name().toLowerCase())
                .displayName(persona.name())
                .role(persona.role)
                .merchantId(persona.merchantId)
                .permissions(persona.permissions)
                .build();
        return builder.with(authentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of())));
    }

    private String code(String body) {
        if (body == null || body.isBlank()) return "";
        var node = jsonMapper.readTree(body).get("code");
        return node == null ? "" : node.asString();
    }
}
