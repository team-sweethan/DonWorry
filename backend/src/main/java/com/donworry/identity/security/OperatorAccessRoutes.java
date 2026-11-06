package com.donworry.identity.security;

import com.donworry.identity.api.OperatorAccessDeniedType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

// 메서드까지 일치하는 사장님 전용 경로를 인가와 403 분류가 함께 쓴다.
public final class OperatorAccessRoutes {
    private OperatorAccessRoutes() {}

    static String[] forecastPaths() {
        return new String[] {"/api/v1/settlements/cash-flow/forecast", "/api/v1/settlements/cash-flow/shortages"};
    }

    static RequestMatcher[] integration() {
        var paths = PathPatternRequestMatcher.withDefaults();
        return new RequestMatcher[] {
            paths.matcher(HttpMethod.GET, "/api/owner/integration"),
            paths.matcher(HttpMethod.POST, "/api/owner/integration/keys"),
            paths.matcher(HttpMethod.POST, "/api/owner/integration/keys/reveal"),
            paths.matcher(HttpMethod.POST, "/api/owner/integration/keys/reissue")
        };
    }

    static RequestMatcher[] finance() {
        var paths = PathPatternRequestMatcher.withDefaults();
        return new RequestMatcher[] {
            paths.matcher(HttpMethod.GET, "/api/v1/settlements/report"),
            paths.matcher(HttpMethod.GET, "/api/v1/settlements/cash-flow/settings"),
            paths.matcher(HttpMethod.PUT, "/api/v1/settlements/cash-flow/settings"),
            paths.matcher(HttpMethod.GET, "/api/v1/settlements/cash-flow/expenses"),
            paths.matcher(HttpMethod.POST, "/api/v1/settlements/cash-flow/expenses"),
            paths.matcher(HttpMethod.PUT, "/api/v1/settlements/cash-flow/expenses/{id}"),
            paths.matcher(HttpMethod.DELETE, "/api/v1/settlements/cash-flow/expenses/{id}"),
            paths.matcher(HttpMethod.PUT, "/api/v1/settlements/cash-flow/expenses/{id}/completion"),
            paths.matcher(HttpMethod.GET, "/api/v1/settlements/cash-flow/recurring-expenses"),
            paths.matcher(HttpMethod.POST, "/api/v1/settlements/cash-flow/recurring-expenses"),
            paths.matcher(HttpMethod.GET, "/api/v1/settlements/cash-flow/recurring-expenses/occurrences"),
            paths.matcher(HttpMethod.DELETE, "/api/v1/settlements/cash-flow/recurring-expenses/{id}"),
            paths.matcher(
                    HttpMethod.PUT,
                    "/api/v1/settlements/cash-flow/recurring-expenses/{id}/occurrences/{date}/completion")
        };
    }

    static RequestMatcher[] cases() {
        var paths = PathPatternRequestMatcher.withDefaults();
        return new RequestMatcher[] {
            paths.matcher(HttpMethod.GET, "/api/merchant/security-cases"),
            paths.matcher(HttpMethod.GET, "/api/merchant/security-cases/{caseId}")
        };
    }

    static OperatorAccessDeniedType deniedType(HttpServletRequest request) {
        for (var group : new RequestMatcher[][] {integration(), finance(), cases()}) {
            for (var route : group) {
                if (route.matches(request)) return OperatorAccessDeniedType.PERMISSION_MISSING;
            }
        }
        var paths = PathPatternRequestMatcher.withDefaults();
        for (var path : forecastPaths()) {
            if (paths.matcher(HttpMethod.GET, path).matches(request)) {
                return OperatorAccessDeniedType.PERMISSION_MISSING;
            }
        }
        return OperatorAccessDeniedType.OUT_OF_ROLE;
    }

    public static boolean isOperatorForbiddenFrontendPath(String path) {
        if (path.contains("//")) return false;
        return "/admin".equals(path)
                || path.startsWith("/admin/")
                || "/security".equals(path)
                || path.startsWith("/security/");
    }
}
