package com.donworry.settlement.service;

import com.donworry.settlement.dto.*;
import com.donworry.settlement.entity.*;
import com.donworry.settlement.repository.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CashFlowManagementService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private final CashFlowSettingRepository settings;
    private final PlannedExpenseRepository expenses;

    public static void validateMerchant(Long merchant) {
        if (merchant == null || merchant <= 0) throw new IllegalArgumentException("가맹점 정보가 필요합니다.");
    }

    public static void validatePeriod(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= 366)
            throw new IllegalArgumentException("조회 기간은 시작일 이후 최대 366일이어야 합니다.");
    }

    @Transactional(readOnly = true)
    public Optional<CashFlowSettingResponse> settings(Long merchant) {
        validateMerchant(merchant);
        return settings.findByMerchantId(merchant)
                .map(s -> new CashFlowSettingResponse(s.getOpeningBalance(), s.getBaseDate()));
    }

    @Transactional
    public CashFlowSettingResponse saveSettings(Long merchant, Long balance, LocalDate date) {
        validateMerchant(merchant);
        if (balance == null || balance < 0 || balance > 9007199254740991L || date == null)
            throw new IllegalArgumentException("0 이상의 시작 잔액과 기준일이 필요합니다.");
        var existing = settings.findByMerchantId(merchant);
        if (existing.isPresent()) return unchanged(existing.get(), balance, date);
        if (!date.equals(LocalDate.now(ZONE))) throw new IllegalArgumentException("최초 잔액 기준일은 한국 시간 오늘이어야 합니다.");
        settings.ensureInitial(merchant, balance, date, LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
        // 최초 요청이 겹친 경우에도 현재 커밋된 행을 잠금 조회해 한 값만 확정한다.
        return unchanged(settings.findForUpdate(merchant).orElseThrow(), balance, date);
    }

    private CashFlowSettingResponse unchanged(CashFlowSetting value, Long balance, LocalDate date) {
        if (!value.getOpeningBalance().equals(balance) || !value.getBaseDate().equals(date))
            throw new SettlementRequestException(409, "SETTLEMENT_SETTING_IMMUTABLE", "최초 시작 잔액과 기준일은 변경할 수 없습니다.");
        return new CashFlowSettingResponse(value.getOpeningBalance(), value.getBaseDate());
    }

    @Transactional(readOnly = true)
    public List<ExpenseResponse> expenses(Long merchant, LocalDate from, LocalDate to) {
        validateMerchant(merchant);
        validatePeriod(from, to);
        return expenses
                .findForPeriod(
                        merchant,
                        from,
                        to,
                        from.atStartOfDay(ZONE).toInstant(),
                        to.plusDays(1).atStartOfDay(ZONE).toInstant())
                .stream()
                .map(ExpenseResponse::from)
                .toList();
    }

    @Transactional
    public ExpenseResponse createExpense(Long merchant, ExpenseRequest request) {
        validateMerchant(merchant);
        requireRequest(request);
        requireSettingDate(merchant, request.expenseDate());
        var expense = PlannedExpense.planned(
                merchant,
                request.title(),
                request.expenseDate(),
                request.amount(),
                category(request.category()),
                request.memo(),
                Instant.now());
        return ExpenseResponse.from(expenses.save(expense));
    }

    @Transactional
    public ExpenseResponse updateExpense(Long merchant, Long id, ExpenseRequest request) {
        validateMerchant(merchant);
        requireRequest(request);
        requireSettingDate(merchant, request.expenseDate());
        var expense = owned(merchant, id);
        if (expense.getDeletedAt() != null)
            throw new SettlementRequestException(404, "SETTLEMENT_EXPENSE_NOT_FOUND", "지출을 찾을 수 없습니다.");
        expense.revise(
                request.title(),
                request.expenseDate(),
                request.amount(),
                category(request.category()),
                request.memo(),
                Instant.now());
        return ExpenseResponse.from(expense);
    }

    @Transactional
    public void deleteExpense(Long merchant, Long id) {
        validateMerchant(merchant);
        owned(merchant, id).remove(Instant.now());
    }

    @Transactional
    public ExpenseResponse completeExpense(Long merchant, Long id) {
        validateMerchant(merchant);
        var expense = owned(merchant, id);
        if (expense.getDeletedAt() != null)
            throw new SettlementRequestException(404, "SETTLEMENT_EXPENSE_NOT_FOUND", "지출을 찾을 수 없습니다.");
        expense.complete(Instant.now());
        return ExpenseResponse.from(expense);
    }

    private void requireSettingDate(Long merchant, LocalDate date) {
        var setting = settings(merchant)
                .orElseThrow(() ->
                        new SettlementRequestException(409, "SETTLEMENT_SETTING_REQUIRED", "최초 시작 잔액을 먼저 설정해 주세요."));
        if (date == null || date.isBefore(setting.baseDate()))
            throw new IllegalArgumentException("지출 예정일은 시작 잔액 기준일 이후여야 합니다.");
    }

    private PlannedExpense owned(Long merchant, Long id) {
        if (id == null || id <= 0) throw new IllegalArgumentException("지출 ID는 양수여야 합니다.");
        return expenses.findOwnedForUpdate(id, merchant)
                .orElseThrow(
                        () -> new SettlementRequestException(404, "SETTLEMENT_EXPENSE_NOT_FOUND", "지출을 찾을 수 없습니다."));
    }

    private void requireRequest(ExpenseRequest request) {
        if (request == null) throw new IllegalArgumentException("지출 정보가 필요합니다.");
    }

    private ExpenseCategory category(String name) {
        try {
            return ExpenseCategory.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("지출 유형이 올바르지 않습니다.");
        }
    }
}
