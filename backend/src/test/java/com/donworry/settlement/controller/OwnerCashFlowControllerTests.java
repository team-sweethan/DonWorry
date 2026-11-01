package com.donworry.settlement.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.donworry.settlement.dto.ExpenseResponse;
import com.donworry.settlement.service.*;
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
        var mvc = MockMvcBuilders.standaloneSetup(new OwnerCashFlowController(access, management))
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
        var mvc = MockMvcBuilders.standaloneSetup(new OwnerCashFlowController(access, management))
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
}
