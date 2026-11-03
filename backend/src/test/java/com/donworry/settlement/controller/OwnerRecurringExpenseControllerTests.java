package com.donworry.settlement.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.donworry.settlement.dto.RecurringExpenseOccurrence;
import com.donworry.settlement.service.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OwnerRecurringExpenseControllerTests {
    @Test
    void complete_whenMerchantSpoofed_usesAuthenticatedMerchant() throws Exception {
        var access = mock(SettlementOwnerAccessService.class);
        when(access.requireMerchant()).thenReturn(2L);
        var service = mock(RecurringExpenseService.class);
        when(service.complete(2L, 10L, LocalDate.of(2026, 10, 30)))
                .thenReturn(RecurringExpenseOccurrence.builder()
                        .recurringExpenseId(10L)
                        .build());
        var mvc = MockMvcBuilders.standaloneSetup(new OwnerRecurringExpenseController(access, service))
                .setControllerAdvice(new PickupExceptionHandler())
                .build();
        mvc.perform(put("/api/v1/settlements/cash-flow/recurring-expenses/10/occurrences/2026-10-30/completion")
                        .param("merchantId", "3"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(service).complete(2L, 10L, LocalDate.of(2026, 10, 30));
    }

    @Test
    void create_whenAmountInvalid_rejectsBeforeService() throws Exception {
        var access = mock(SettlementOwnerAccessService.class);
        when(access.requireMerchant()).thenReturn(2L);
        var service = mock(RecurringExpenseService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new OwnerRecurringExpenseController(access, service))
                .setControllerAdvice(new PickupExceptionHandler())
                .build();
        mvc.perform(
                        post("/api/v1/settlements/cash-flow/recurring-expenses")
                                .contentType("application/json")
                                .content(
                                        "{\"title\":\"재료\",\"amount\":0,\"category\":\"MATERIAL\",\"frequency\":\"DAILY\",\"startDate\":\"2026-10-30\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
