package com.donworry.settlement.service;

import com.donworry.settlement.dto.*;
import com.donworry.settlement.entity.*;
import com.donworry.settlement.repository.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecurringExpenseService {
    private final RecurringExpenseRepository repository;
    private final RecurringExpenseExpander expander;
    private final RecurringExpensePaymentRepository payments;
    private final CashFlowSettingRepository settings;

    @Transactional(readOnly = true)
    public List<RecurringExpenseResponse> list(Long merchant) {
        CashFlowManagementService.validateMerchant(merchant);
        return repository.findAllByMerchantIdOrderByRecurringExpenseIdAsc(merchant).stream()
                .map(RecurringExpenseResponse::from)
                .toList();
    }

    @Transactional
    public RecurringExpenseResponse create(Long merchant, RecurringExpenseRequest r) {
        CashFlowManagementService.validateMerchant(merchant);
        if (r == null
                || r.category() == null
                || r.frequency() == null
                || r.startDate() == null
                || r.startDate().isBefore(LocalDate.now(SettlementReportService.ZONE)))
            throw new IllegalArgumentException("반복 시작일은 오늘 이후여야 합니다.");
        var initial = settings.findByMerchantId(merchant)
                .orElseThrow(() ->
                        new SettlementRequestException(409, "SETTLEMENT_SETTING_REQUIRED", "최초 시작 잔액을 먼저 설정해 주세요."));
        if (r.startDate().isBefore(initial.getBaseDate()))
            throw new IllegalArgumentException("반복 시작일은 잔액 기준일 이후여야 합니다.");
        var entity = RecurringExpense.recurring(
                merchant,
                r.title(),
                r.amount(),
                ExpenseCategory.valueOf(r.category()),
                r.memo(),
                ExpenseFrequency.valueOf(r.frequency()),
                r.startDate(),
                r.endDate(),
                Instant.now());
        return RecurringExpenseResponse.from(repository.save(entity));
    }

    @Transactional
    public void stop(Long merchant, Long id) {
        CashFlowManagementService.validateMerchant(merchant);
        if (id == null || id <= 0) throw new IllegalArgumentException("반복 지출 ID가 필요합니다.");
        var e = repository
                .findOwnedForUpdate(id, merchant)
                .orElseThrow(() ->
                        new SettlementRequestException(404, "SETTLEMENT_RECURRING_NOT_FOUND", "반복 지출을 찾을 수 없습니다."));
        e.stop(LocalDate.now(SettlementReportService.ZONE), Instant.now());
    }

    @Transactional(readOnly = true)
    public List<RecurringExpenseOccurrence> occurrences(Long merchant, LocalDate from, LocalDate to) {
        CashFlowManagementService.validateMerchant(merchant);
        CashFlowManagementService.validatePeriod(from, to);
        var pending = expander.expand(
                repository.findApplicable(merchant, from, to).stream()
                        .map(RecurringExpenseResponse::from)
                        .toList(),
                from,
                to);
        // 예정일이 기간 안에 있어도 다른 날짜에 이미 지출한 회차는 다시 예정으로 계산하지 않는다.
        var paidDue = payments.findByMerchantIdAndOccurrenceDateBetween(merchant, from, to, PageRequest.of(0, 10001));
        var paidActual = payments.findByMerchantIdAndPaidAtGreaterThanEqualAndPaidAtLessThan(
                merchant,
                from.atStartOfDay(SettlementReportService.ZONE).toInstant(),
                to.plusDays(1).atStartOfDay(SettlementReportService.ZONE).toInstant(),
                PageRequest.of(0, 10001));
        if (paidDue.size() > 10000 || paidActual.size() > 10000) throw tooLarge();
        var paidKeys = new HashSet<String>();
        paidDue.forEach(p -> paidKeys.add(key(p.getRecurringExpenseId(), p.getOccurrenceDate())));
        var result = new ArrayList<RecurringExpenseOccurrence>();
        pending.stream()
                .filter(p -> !paidKeys.contains(key(p.recurringExpenseId(), p.expenseDate())))
                .forEach(result::add);
        paidActual.stream().map(this::paidResponse).forEach(result::add);
        if (result.size() > 10000) throw tooLarge();
        result.sort(Comparator.comparing(
                        (RecurringExpenseOccurrence p) -> p.paidDate() == null ? p.expenseDate() : p.paidDate())
                .thenComparing(RecurringExpenseOccurrence::recurringExpenseId));
        return List.copyOf(result);
    }

    @Transactional
    public RecurringExpenseOccurrence complete(Long merchant, Long id, LocalDate date) {
        CashFlowManagementService.validateMerchant(merchant);
        if (id == null || id <= 0 || date == null) throw new IllegalArgumentException("정기 지출과 발생일이 필요합니다.");
        // 중단과 완료를 같은 부모 행 잠금으로 직렬화하고 재전달은 기존 완료 기록을 반환한다.
        var rule = repository
                .findOwnedForUpdate(id, merchant)
                .orElseThrow(() ->
                        new SettlementRequestException(404, "SETTLEMENT_RECURRING_NOT_FOUND", "정기 지출을 찾을 수 없습니다."));
        var existing = payments.findByRecurringExpenseIdAndOccurrenceDate(id, date);
        if (existing.isPresent()) return paidResponse(existing.get());
        if (expander.expand(List.of(RecurringExpenseResponse.from(rule)), date, date)
                .isEmpty())
            throw new SettlementRequestException(
                    409, "SETTLEMENT_RECURRING_OCCURRENCE_INVALID", "유효한 정기 지출 발생일이 아닙니다.");
        return paidResponse(payments.save(RecurringExpensePayment.completed(rule, date, Instant.now())));
    }

    private String key(Long id, LocalDate date) {
        return id + ":" + date;
    }

    private SettlementRequestException tooLarge() {
        return new SettlementRequestException(422, "SETTLEMENT_RECURRING_PERIOD_TOO_LARGE", "조회 기간을 줄여 주세요.");
    }

    private RecurringExpenseOccurrence paidResponse(RecurringExpensePayment p) {
        return RecurringExpenseOccurrence.builder()
                .recurringExpenseId(p.getRecurringExpenseId())
                .title(p.getTitle())
                .expenseDate(p.getOccurrenceDate())
                .amount(p.getAmount())
                .category(p.getCategory().name())
                .memo(p.getMemo())
                .occurrencePaymentId(p.getPaymentId())
                .paidAt(p.getPaidAt())
                .paidDate(p.getPaidAt().atZone(SettlementReportService.ZONE).toLocalDate())
                .build();
    }
}
