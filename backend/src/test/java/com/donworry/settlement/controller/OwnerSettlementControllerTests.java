package com.donworry.settlement.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffSummary;
import com.donworry.settlement.dto.SettlementReportResponse;
import com.donworry.settlement.service.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OwnerSettlementControllerTests {
    // 인증 없는 요청은 DB 조회 전에 차단한다.
    @Test
    void report_whenUnauthenticated_returns401() throws Exception {
        var staff = mock(CurrentStaff.class);
        var reports = mock(SettlementReportService.class);
        var mvc = MockMvcBuilders.standaloneSetup(
                        new OwnerSettlementController(new SettlementOwnerAccessService(staff), reports))
                .setControllerAdvice(new PickupExceptionHandler())
                .build();
        mvc.perform(get("/api/v1/settlements/report?from=2026-11-01&to=2026-11-02"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reports);
    }

    // 요청 매개변수나 모델 속성으로 가맹점을 바꾸려 해도 세션의 가맹점만 조회한다.
    @Test
    void report_whenMerchantSpoofed_usesSessionMerchant() throws Exception {
        var staff = mock(CurrentStaff.class);
        when(staff.findCurrent())
                .thenReturn(Optional.of(StaffSummary.builder()
                        .role("OPERATOR")
                        .merchantId(2L)
                        .permissions(List.of("FINANCE_VIEW"))
                        .build()));
        var reports = mock(SettlementReportService.class);
        var from = LocalDate.of(2026, 11, 1);
        var to = from.plusDays(1);
        when(reports.report(2L, from, to))
                .thenReturn(SettlementReportResponse.builder()
                        .from(from)
                        .to(to)
                        .days(List.of())
                        .entries(List.of())
                        .build());
        var mvc = MockMvcBuilders.standaloneSetup(
                        new OwnerSettlementController(new SettlementOwnerAccessService(staff), reports))
                .setControllerAdvice(new PickupExceptionHandler())
                .build();
        mvc.perform(get("/api/v1/settlements/report")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("merchantId", "3")
                        .param("settlement.ownerMerchant", "3"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(reports).report(2L, from, to);
    }

    // 직원 역할만 있거나 자금 조회 권한이 없으면 사장님 조회를 허용하지 않는다.
    @Test
    void report_whenRoleOrPermissionMissing_returns403() throws Exception {
        var staff = mock(CurrentStaff.class);
        var reports = mock(SettlementReportService.class);
        var mvc = MockMvcBuilders.standaloneSetup(
                        new OwnerSettlementController(new SettlementOwnerAccessService(staff), reports))
                .setControllerAdvice(new PickupExceptionHandler())
                .build();
        for (var person : List.of(
                StaffSummary.builder()
                        .role("SECURITY_STAFF")
                        .merchantId(2L)
                        .permissions(List.of("FINANCE_VIEW"))
                        .build(),
                StaffSummary.builder()
                        .role("OPERATOR")
                        .merchantId(2L)
                        .permissions(List.of())
                        .build())) {
            when(staff.findCurrent()).thenReturn(Optional.of(person));
            mvc.perform(get("/api/v1/settlements/report?from=2026-11-01&to=2026-11-02"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(reports);
    }

    // 명시적으로 활성화하지 않은 환경에는 새 조회 경로를 등록하지 않는다.
    @Test
    void context_whenDisabled_doesNotRegisterController() {
        new ApplicationContextRunner()
                .withUserConfiguration(OwnerSettlementController.class)
                .run(c -> assertThat(c).doesNotHaveBean(OwnerSettlementController.class));
    }
}
