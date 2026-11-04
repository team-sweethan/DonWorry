package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.identity.api.OperatorAccessDeniedType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

class OperatorAccessRoutesTests {
    // 허용된 메서드와 사장님 경로의 거절은 권한 부족으로 분류한다
    @ParameterizedTest
    @CsvSource({
        "GET,/api/owner/integration",
        "POST,/api/owner/integration/keys/reveal",
        "GET,/api/v1/settlements/cash-flow/forecast",
        "GET,/api/v1/settlements/cash-flow/shortages",
        "GET,/api/v1/settlements/report",
        "DELETE,/api/v1/settlements/cash-flow/expenses/12",
        "PUT,/api/v1/settlements/cash-flow/recurring-expenses/12/occurrences/2026-11-04/completion",
        "GET,/api/merchant/security-cases",
        "GET,/api/merchant/security-cases/12"
    })
    void deniedType_whenOperatorRoute_returnsPermissionMissing(String method, String path) {
        assertThat(OperatorAccessRoutes.deniedType(request(method, path)))
                .isEqualTo(OperatorAccessDeniedType.PERMISSION_MISSING);
    }

    // 접두사만 같거나 메서드가 다른 경로를 사장님 권한 부족으로 오인하지 않는다
    @ParameterizedTest
    @CsvSource({
        "GET,/api/admin/users",
        "GET,/api/security-cases",
        "GET,/api/missing",
        "POST,/api/v1/settlements/cash-flow/forecast",
        "HEAD,/api/v1/settlements/cash-flow/forecast",
        "POST,/api/v1/settlements/report",
        "GET,/api/owner/integration/unknown",
        "PUT,/api/merchant/security-cases/12",
        "GET,/api/merchant/security-cases/12/unknown"
    })
    void deniedType_whenOutsideOperatorRoutes_returnsOutOfRole(String method, String path) {
        assertThat(OperatorAccessRoutes.deniedType(request(method, path)))
                .isEqualTo(OperatorAccessDeniedType.OUT_OF_ROLE);
    }

    private MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}
