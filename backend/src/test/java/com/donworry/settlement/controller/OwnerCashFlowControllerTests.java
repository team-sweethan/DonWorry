package com.donworry.settlement.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.donworry.settlement.dto.CashFlowForecastResponse;
import com.donworry.settlement.dto.ExpenseResponse;
import com.donworry.settlement.service.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OwnerCashFlowControllerTests {
    // 가맹점 ID를 요청에서 조작해도 인증된 가맹점의 지출 완료만 요청한다.
    @Test
    void complete_whenMerchantSpoofed_usesAuthenticatedMerchant() throws Exception {
        var access = mock(SettlementOwnerAccessService.class);
        when(access.requireMerchant()).thenReturn(2L);
        var management = mock(CashFlowManagementService.class);
        when(management.completeExpense(2L, 10L))
                .thenReturn(ExpenseResponse.builder().expenseId(10L).build());
        var mvc = MockMvcBuilders.standaloneSetup(
                        new OwnerCashFlowController(access, management, mock(CashFlowForecastService.class)))
                .setControllerAdvice(new PickupExceptionHandler())
                .build();
        mvc.perform(put("/api/v1/settlements/cash-flow/expenses/10/completion").param("merchantId", "3"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(management).completeExpense(2L, 10L);
    }

    // 음수 초기 잔액이나 0원 지출은 서비스 호출 전에 거절한다.
    @Test
    void write_whenAmountInvalid_returns400() throws Exception {
        var access = mock(SettlementOwnerAccessService.class);
        when(access.requireMerchant()).thenReturn(2L);
        var management = mock(CashFlowManagementService.class);
        var mvc = MockMvcBuilders.standaloneSetup(
                        new OwnerCashFlowController(access, management, mock(CashFlowForecastService.class)))
                .setControllerAdvice(new PickupExceptionHandler())
                .build();
        mvc.perform(put("/api/v1/settlements/cash-flow/settings")
                        .contentType("application/json")
                        .content("{\"openingBalance\":-1,\"baseDate\":\"2026-10-30\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/v1/settlements/cash-flow/expenses")
                                .contentType("application/json")
                                .content(
                                        "{\"title\":\"재료\",\"expenseDate\":\"2026-10-30\",\"amount\":0,\"category\":\"MATERIAL\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(management);
    }
    // 요청 merchantId를 무시하고 인증된 가맹점으로 예측·부족 구간을 조회한다.
    @Test
    void forecast_whenMerchantSpoofed_usesAuthenticatedMerchantAndNoStore() throws Exception {
        var access = mock(SettlementOwnerAccessService.class);
        when(access.requireMerchant()).thenReturn(2L);
        var forecasts = mock(CashFlowForecastService.class);
        var day = LocalDate.of(2026, 11, 2);
        when(forecasts.forecast(2L, day, day))
                .thenReturn(CashFlowForecastResponse.builder()
                        .currentBalance(100L)
                        .carryForwardMovements(List.of())
                        .days(List.of())
                        .shortages(List.of())
                        .build());
        var mvc = MockMvcBuilders.standaloneSetup(
                        new OwnerCashFlowController(access, mock(CashFlowManagementService.class), forecasts))
                .setControllerAdvice(new PickupExceptionHandler())
                .build();
        mvc.perform(get("/api/v1/settlements/cash-flow/forecast")
                        .param("from", day.toString())
                        .param("to", day.toString())
                        .param("merchantId", "999"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.currentBalance").value(100));
        mvc.perform(get("/api/v1/settlements/cash-flow/shortages")
                        .param("from", day.toString())
                        .param("to", day.toString()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        verify(forecasts, times(2)).forecast(2L, day, day);
    }

    // 로그인·권한 오류를 그대로 반환하고 예측 데이터를 읽지 않는다.
    @Test
    void forecast_whenAccessDenied_doesNotReadFinance() throws Exception {
        for (int code : new int[] {401, 403}) {
            var access = mock(SettlementOwnerAccessService.class);
            when(access.requireMerchant())
                    .thenThrow(new SettlementRequestException(code, "SETTLEMENT_FORBIDDEN", "접근 불가"));
            var forecasts = mock(CashFlowForecastService.class);
            var mvc = MockMvcBuilders.standaloneSetup(
                            new OwnerCashFlowController(access, mock(CashFlowManagementService.class), forecasts))
                    .setControllerAdvice(new PickupExceptionHandler())
                    .build();
            mvc.perform(get("/api/v1/settlements/cash-flow/forecast")
                            .param("from", "2026-11-02")
                            .param("to", "2026-11-02"))
                    .andExpect(status().is(code))
                    .andExpect(header().string("Cache-Control", "no-store"));
            verifyNoInteractions(forecasts);
        }
    }

    // 잘못된 날짜 형식·필수 날짜 누락은 400이고 범위 초과 오류는 422로 전달한다.
    @Test
    void forecast_whenDatesInvalidOrOverflow_returnsProblemDetail() throws Exception {
        var access = mock(SettlementOwnerAccessService.class);
        when(access.requireMerchant()).thenReturn(2L);
        var forecasts = mock(CashFlowForecastService.class);
        var mvc = MockMvcBuilders.standaloneSetup(
                        new OwnerCashFlowController(access, mock(CashFlowManagementService.class), forecasts))
                .setControllerAdvice(new PickupExceptionHandler())
                .build();
        mvc.perform(get("/api/v1/settlements/cash-flow/forecast")
                        .param("from", "invalid")
                        .param("to", "2026-11-02"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/settlements/cash-flow/forecast").param("from", "2026-11-02"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(forecasts);
        when(forecasts.forecast(anyLong(), any(), any()))
                .thenThrow(new SettlementRequestException(422, "SETTLEMENT_CASH_FLOW_AMOUNT_OVERFLOW", "범위 초과"));
        mvc.perform(get("/api/v1/settlements/cash-flow/forecast")
                        .param("from", "2026-11-02")
                        .param("to", "2026-11-02"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_CASH_FLOW_AMOUNT_OVERFLOW"));
    }
}
