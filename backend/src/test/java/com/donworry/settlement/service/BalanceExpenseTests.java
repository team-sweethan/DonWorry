package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.settlement.entity.*;
import com.donworry.settlement.repository.*;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class BalanceExpenseTests {
    // 최초 설정은 같은 내용만 재전달할 수 있고 변경 시 DB 쓰기를 하지 않는다.
    @Test
    void saveSettings_whenAlreadySet_preservesOriginal() {
        var settings = mock(CashFlowSettingRepository.class);
        var costs = mock(PlannedExpenseRepository.class);
        var value = org.springframework.beans.BeanUtils.instantiateClass(CashFlowSetting.class);
        var date = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
        ReflectionTestUtils.setField(value, "openingBalance", 1000L);
        ReflectionTestUtils.setField(value, "baseDate", date);
        when(settings.findByMerchantId(1L)).thenReturn(Optional.of(value));
        var service = new CashFlowManagementService(settings, costs);
        assertThat(service.saveSettings(1L, 1000L, date).baseDate()).isEqualTo(date);
        assertThatThrownBy(() -> service.saveSettings(1L, 2000L, date)).isInstanceOf(SettlementRequestException.class);
        verify(settings, never()).ensureInitial(any(), any(), any(), any());
    }

    // 완료 시각은 한 번만 확정하며 이미 지출한 금액을 수정·삭제하지 않는다.
    @Test
    void complete_whenRepeated_preservesTimeAndRejectsMutation() {
        var at = Instant.parse("2026-10-30T01:00:00Z");
        var expense =
                PlannedExpense.planned(1L, "재료", LocalDate.of(2026, 10, 30), 100L, ExpenseCategory.MATERIAL, null, at);
        expense.complete(at);
        expense.complete(at.plusSeconds(60));
        assertThat(expense.getPaidAt()).isEqualTo(at);
        assertThatIllegalStateException().isThrownBy(() -> expense.remove(at));
        assertThatIllegalStateException()
                .isThrownBy(
                        () -> expense.revise("변경", LocalDate.of(2026, 10, 30), 200L, ExpenseCategory.OTHER, null, at));
    }

    // 삭제된 지출을 완료하여 다시 집계에 포함하지 않는다.
    @Test
    void complete_whenDeleted_rejects() {
        var at = Instant.now();
        var expense = PlannedExpense.planned(1L, "임대료", LocalDate.now(), 100L, ExpenseCategory.RENT, null, at);
        expense.remove(at);
        assertThatIllegalStateException().isThrownBy(() -> expense.complete(at));
    }

    // 다른 가맹점의 지출은 완료 처리 전에 404로 차단한다.
    @Test
    void completeExpense_whenNotOwned_rejectsWithoutWrite() {
        var settings = mock(CashFlowSettingRepository.class);
        var costs = mock(PlannedExpenseRepository.class);
        assertThatThrownBy(() -> new CashFlowManagementService(settings, costs).completeExpense(2L, 10L))
                .isInstanceOf(SettlementRequestException.class);
        verify(costs).findOwnedForUpdate(10L, 2L);
        verifyNoInteractions(settings);
    }
}
