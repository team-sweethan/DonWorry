package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.settlement.dto.ExpenseRequest;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
class BalanceExpenseMariaDbTests {
    private static final long MERCHANT = 71801L;

    @Autowired
    CashFlowManagementService management;

    @Autowired
    JdbcTemplate jdbc;

    private LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM planned_expenses WHERE merchant_id IN (?,?)", MERCHANT, MERCHANT + 1);
        jdbc.update("DELETE FROM cash_flow_settings WHERE merchant_id IN (?,?)", MERCHANT, MERCHANT + 1);
    }

    // 동시 최초 설정은 서로 다른 값으로 덮어쓰지 않고 한 값만 확정한다.
    @Test
    void saveSettings_whenConcurrent_keepsOneValue() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> attempt(barrier, 1000L));
            var b = pool.submit(() -> attempt(barrier, 2000L));
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SAVED", "CONFLICT");
        }
        var saved = management.settings(MERCHANT).orElseThrow();
        management.saveSettings(MERCHANT, saved.openingBalance(), saved.baseDate());
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM cash_flow_settings WHERE merchant_id=?", Long.class, MERCHANT))
                .isEqualTo(1);
    }

    private String attempt(CyclicBarrier barrier, long amount) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            management.saveSettings(MERCHANT, amount, today());
            return "SAVED";
        } catch (SettlementRequestException e) {
            return "CONFLICT";
        }
    }

    // 지출은 예정일과 실제 완료일을 구분하고 중복 완료·타 가맹점 변경을 막는다.
    @Test
    void expense_whenCompleted_movesToActualDayAndPreservesAmounts() {
        management.saveSettings(MERCHANT, 1000L, today());
        var request = ExpenseRequest.builder()
                .title("재료비")
                .expenseDate(today().plusDays(2))
                .amount(300L)
                .category("MATERIAL")
                .build();
        var expense = management.createExpense(MERCHANT, request);
        assertThat(expense.paidAt()).isNull();
        assertThat(management.expenses(MERCHANT, today(), today())).isEmpty();
        assertThatThrownBy(() -> management.completeExpense(MERCHANT + 1, expense.expenseId()))
                .isInstanceOf(SettlementRequestException.class);
        var paid = management.completeExpense(MERCHANT, expense.expenseId());
        assertThat(management.completeExpense(MERCHANT, expense.expenseId()).paidAt())
                .isEqualTo(paid.paidAt());
        assertThat(paid.expenseDate()).isEqualTo(today().plusDays(2));
        assertThat(management.expenses(MERCHANT, today(), today())).hasSize(1);
        assertThat(management.expenses(MERCHANT, today().plusDays(2), today().plusDays(2)))
                .isEmpty();
        assertThatThrownBy(() -> management.updateExpense(MERCHANT, expense.expenseId(), request))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> management.deleteExpense(MERCHANT, expense.expenseId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT amount FROM planned_expenses WHERE expense_id=?", Long.class, expense.expenseId()))
                .isEqualTo(300L);
    }

    // 최초 설정 전 지출 생성과 기준일 소급 변경을 허용하지 않는다.
    @Test
    void createExpense_whenSettingMissing_rejects() {
        var request = ExpenseRequest.builder()
                .title("재료비")
                .expenseDate(today())
                .amount(300L)
                .category("MATERIAL")
                .build();
        assertThatThrownBy(() -> management.createExpense(MERCHANT, request))
                .isInstanceOf(SettlementRequestException.class);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> management.saveSettings(MERCHANT, 1000L, today().minusDays(1)));
        assertThat(management.settings(MERCHANT)).isEmpty();
    }
}
