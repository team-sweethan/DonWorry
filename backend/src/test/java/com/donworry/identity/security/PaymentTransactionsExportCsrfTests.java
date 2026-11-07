package com.donworry.identity.security;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.donworry.identity.entity.UserRole;
import com.donworry.identity.service.LoginHistoryService;
import com.donworry.identity.service.OperatorAccessDenialService;
import com.donworry.payment.controller.PaymentTransactionsController;
import com.donworry.payment.service.PaymentTransactionsExportService;
import com.donworry.payment.service.PaymentTransactionsQueryService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// 거래 내역 내보내기 CSRF (#651 Codex 리뷰) — 경보 중이면 계정 정지까지 일으키는 요청이라
// 전역 CSRF 설정을 꺼도 토큰 없는 POST는 내보내기 서비스(문지기 · 정지)에 닿지 않는다
@WebMvcTest(controllers = PaymentTransactionsController.class, properties = "security.csrf.enabled=false")
@Import({
    SecurityConfig.class,
    JsonAuthenticationEntryPoint.class,
    JsonAccessDeniedHandler.class,
    SecurityProblemResponseWriter.class
})
class PaymentTransactionsExportCsrfTests {
    private static final String PATH = "/api/payment-transactions/export";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OperatorAccessDenialService accessDenials;

    @MockitoBean
    UserDetailsService users;

    @MockitoBean
    LoginHistoryService histories;

    @MockitoBean
    PaymentTransactionsQueryService queryService;

    @MockitoBean
    PaymentTransactionsExportService exportService;

    // 토큰이 없거나 쿠키와 헤더가 다르면 403 CSRF_001이고, 내보내기 서비스는 불리지 않는다(정지 · 경보 · 기록 없음)
    @Test
    void export_whenPostWithoutValidCsrfToken_rejectsBeforeService() throws Exception {
        mvc.perform(post(PATH).with(staff()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_001"));
        mvc.perform(post(PATH)
                        .with(staff())
                        .cookie(new Cookie("XSRF-TOKEN", "valid"))
                        .header("X-XSRF-TOKEN", "wrong"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_001"));
        verifyNoInteractions(exportService);
    }

    private RequestPostProcessor staff() {
        var user = SessionUser.builder()
                .userId(651L)
                .role(UserRole.SECURITY_STAFF)
                .permissions(List.of())
                .build();
        return authentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
    }
}
