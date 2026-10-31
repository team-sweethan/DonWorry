package com.donworry.settlement.entity;

import jakarta.persistence.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "recurring_expense_payments",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_recurring_payment_occurrence",
                        columnNames = {"recurring_expense_id", "occurrence_date"}))
public class RecurringExpensePayment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "recurring_expense_id", nullable = false)
    private Long recurringExpenseId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "occurrence_date", nullable = false)
    private LocalDate occurrenceDate;

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

    @Column(name = "paid_at", nullable = false)
    private Instant paidAt;

    public static RecurringExpensePayment completed(RecurringExpense rule, LocalDate date, Instant now) {
        return RecurringExpensePayment.builder().rule(rule).date(date).now(now).build();
    }

    // 완료 당시의 금액과 설명을 보존하며 생성 이후 수정·삭제 경로를 제공하지 않는다.
    @Builder(access = AccessLevel.PRIVATE)
    private RecurringExpensePayment(RecurringExpense rule, LocalDate date, Instant now) {
        if (rule == null || rule.getRecurringExpenseId() == null || date == null || now == null)
            throw new IllegalArgumentException("저장된 정기 지출과 발생일·완료 시각이 필요합니다.");
        recurringExpenseId = rule.getRecurringExpenseId();
        merchantId = rule.getMerchantId();
        occurrenceDate = date;
        title = rule.getTitle();
        amount = rule.getAmount();
        category = rule.getCategory();
        memo = rule.getMemo();
        paidAt = now.truncatedTo(ChronoUnit.MICROS);
    }
}
