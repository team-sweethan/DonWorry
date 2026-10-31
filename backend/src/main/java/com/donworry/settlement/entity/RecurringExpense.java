package com.donworry.settlement.entity;

import jakarta.persistence.*;
import java.time.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "recurring_expenses")
public class RecurringExpense {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recurring_expense_id")
    private Long recurringExpenseId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private ExpenseCategory category;

    @Column(length = 500)
    private String memo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private ExpenseFrequency frequency;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "stopped_from")
    private LocalDate stoppedFrom;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static RecurringExpense recurring(
            Long merchant,
            String title,
            Long amount,
            ExpenseCategory category,
            String memo,
            ExpenseFrequency frequency,
            LocalDate start,
            LocalDate end,
            Instant now) {
        return RecurringExpense.builder()
                .merchant(merchant)
                .title(title)
                .amount(amount)
                .category(category)
                .memo(memo)
                .frequency(frequency)
                .start(start)
                .end(end)
                .now(now)
                .build();
    }

    @Builder(access = AccessLevel.PRIVATE)
    private RecurringExpense(
            Long merchant,
            String title,
            Long amount,
            ExpenseCategory category,
            String memo,
            ExpenseFrequency frequency,
            LocalDate start,
            LocalDate end,
            Instant now) {
        if (merchant == null
                || merchant <= 0
                || title == null
                || title.isBlank()
                || title.length() > 100
                || amount == null
                || amount <= 0
                || amount > 9007199254740991L
                || category == null
                || frequency == null
                || start == null
                || now == null
                || (memo != null && memo.length() > 500)
                || (end != null && end.isBefore(start))) throw new IllegalArgumentException("반복 지출 입력값이 올바르지 않습니다.");
        this.merchantId = merchant;
        this.title = title.trim();
        this.amount = amount;
        this.category = category;
        this.memo = memo;
        this.frequency = frequency;
        this.startDate = start;
        this.endDate = end;
        this.createdAt = now;
        this.updatedAt = now;
    }
    // 과거 발생 내역을 보존하기 위해 규칙 삭제 대신 적용 중단일을 기록한다.
    public void stop(LocalDate from, Instant now) {
        if (from == null || now == null) throw new IllegalArgumentException("중단일이 필요합니다.");
        if (stoppedFrom == null) {
            stoppedFrom = from;
            updatedAt = now;
        }
    }
}
