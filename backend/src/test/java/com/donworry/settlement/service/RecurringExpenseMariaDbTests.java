package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.settlement.dto.RecurringExpenseRequest;
import com.donworry.settlement.repository.RecurringExpensePaymentRepository;
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
class RecurringExpenseMariaDbTests {
    private static final long MERCHANT = 71901L;

    @Autowired
    RecurringExpenseService service;

    @Autowired
    CashFlowManagementService management;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RecurringExpensePaymentRepository payments;

    private LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM recurring_expense_payments WHERE merchant_id=?", MERCHANT);
        jdbc.update("DELETE FROM recurring_expenses WHERE merchant_id=?", MERCHANT);
        jdbc.update("DELETE FROM cash_flow_settings WHERE merchant_id=?", MERCHANT);
    }

    private Long create(String frequency, LocalDate start) {
        management.saveSettings(MERCHANT, 10000L, today());
        return service.create(MERCHANT, request(frequency, start)).recurringExpenseId();
    }

    private RecurringExpenseRequest request(String frequency, LocalDate start) {
        return RecurringExpenseRequest.builder()
                .title("재료비")
                .amount(300L)
                .category("MATERIAL")
                .frequency(frequency)
                .startDate(start)
                .build();
    }

    // 완료 키 묶음 조회는 선택한 규칙·가맹점·예정일 범위만 반환한다.
    @Test
    void completedOccurrences_whenBatchQueried_filtersMerchantRulesAndDates() {
        var first = create("DAILY", today());
        var second = service.create(MERCHANT, request("DAILY", today())).recurringExpenseId();
        service.complete(MERCHANT, first, today());
        service.complete(MERCHANT, first, today().plusDays(1));
        service.complete(MERCHANT, second, today());
        var keys = payments.findCompletedOccurrences(MERCHANT, List.of(first, second), today(), today());
        assertThat(keys)
                .extracting(RecurringExpensePaymentRepository.CompletedOccurrence::getRecurringExpenseId)
                .containsExactlyInAnyOrder(first, second);
        assertThat(keys)
                .extracting(RecurringExpensePaymentRepository.CompletedOccurrence::getOccurrenceDate)
                .containsOnly(today());
        assertThat(payments.findCompletedOccurrences(MERCHANT, List.of(first), today(), today()))
                .hasSize(1);
        assertThat(payments.findCompletedOccurrences(MERCHANT + 1, List.of(first, second), today(), today()))
                .isEmpty();
    }

    // 같은 회차의 동시 완료는 최초 완료 기록 하나와 동일한 응답을 보존한다.
    @Test
    void complete_whenConcurrent_persistsOnce() throws Exception {
        var id = create("DAILY", today());
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Long> complete = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.complete(MERCHANT, id, today()).occurrencePaymentId();
            };
            var first = pool.submit(complete);
            var second = pool.submit(complete);
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM recurring_expense_payments WHERE recurring_expense_id=?", Long.class, id))
                .isEqualTo(1);
    }

    // 미래 회차를 오늘 지출하면 오늘 실제 지출로만 조회하고 중단 후에도 기록을 보존한다.
    @Test
    void occurrences_whenPaidEarlyAndStopped_keepsActualHistoryWithoutPendingDuplicate() {
        var due = today().plusDays(3);
        var id = create("DAILY", due);
        var paid = service.complete(MERCHANT, id, due);
        assertThat(paid.paidDate()).isEqualTo(today());
        assertThat(service.occurrences(MERCHANT, due, due)).isEmpty();
        service.stop(MERCHANT, id);
        service.stop(MERCHANT, id);
        assertThat(service.occurrences(MERCHANT, today(), today())).containsExactly(paid);
        assertThat(service.complete(MERCHANT, id, due)).isEqualTo(paid);
        assertThat(service.occurrences(MERCHANT, due, due.plusDays(5))).isEmpty();
        assertThatThrownBy(() -> service.complete(MERCHANT, id, due.plusDays(1)))
                .isInstanceOfSatisfying(
                        SettlementRequestException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void complete_whenWrongMerchantOrWrongWeekday_rejects() {
        var id = create("WEEKLY", today());
        assertThatThrownBy(() -> service.complete(MERCHANT + 1, id, today()))
                .isInstanceOfSatisfying(
                        SettlementRequestException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(() -> service.stop(MERCHANT + 1, id)).isInstanceOf(SettlementRequestException.class);
        assertThat(service.list(MERCHANT + 1)).isEmpty();
        assertThatThrownBy(() -> service.complete(MERCHANT, id, today().plusDays(1)))
                .isInstanceOfSatisfying(
                        SettlementRequestException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(service.occurrences(MERCHANT, today(), today().plusDays(7))).hasSize(2);
    }

    @Test
    void create_whenSettingMissingOrAmountInvalid_rejects() {
        assertThatThrownBy(() -> service.create(MERCHANT, request("DAILY", today())))
                .isInstanceOf(SettlementRequestException.class);
        management.saveSettings(MERCHANT, 1000L, today());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.create(
                        MERCHANT,
                        RecurringExpenseRequest.builder()
                                .title("재료")
                                .amount(0L)
                                .category("MATERIAL")
                                .frequency("DAILY")
                                .startDate(today())
                                .build()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.create(MERCHANT, request("DAILY", today().minusDays(1))));
    }
}
