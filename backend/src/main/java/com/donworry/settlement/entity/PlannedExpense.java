package com.donworry.settlement.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "planned_expenses")
public class PlannedExpense {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "expense_id")
    private Long expenseId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "category", nullable = false, length = 30)
    private ExpenseCategory category;

    @Column(name = "memo", length = 500)
    private String memo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    public void complete(Instant now) {
        if (deletedAt != null) throw new IllegalStateException("삭제한 지출은 완료할 수 없습니다.");
        if (paidAt != null) return;
        paidAt = java.util.Objects.requireNonNull(now).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        updatedAt = paidAt;
    }

    public static PlannedExpense planned(
            Long merchantId,
            String title,
            LocalDate date,
            Long amount,
            ExpenseCategory category,
            String memo,
            Instant now) {
        return PlannedExpense.builder()
                .merchantId(merchantId)
                .title(title)
                .date(date)
                .amount(amount)
                .category(category)
                .memo(memo)
                .now(now)
                .build();
    }

    @Builder(access = AccessLevel.PRIVATE)
    private PlannedExpense(
            Long merchantId,
            String title,
            LocalDate date,
            Long amount,
            ExpenseCategory category,
            String memo,
            Instant now) {
        if (merchantId == null || merchantId <= 0) throw new IllegalArgumentException("가맹점 ID는 양수여야 합니다.");
        this.merchantId = merchantId;
        this.createdAt = now;
        revise(title, date, amount, category, memo, now);
    }

    public void revise(String title, LocalDate date, Long amount, ExpenseCategory category, String memo, Instant now) {
        if (deletedAt != null) throw new IllegalStateException("삭제한 지출은 수정할 수 없습니다.");
        if (paidAt != null) throw new IllegalStateException("완료한 지출은 수정할 수 없습니다.");
        if (title == null
                || title.isBlank()
                || title.length() > 100
                || date == null
                || amount == null
                || amount <= 0
                || amount > 9007199254740991L
                || category == null
                || (memo != null && memo.length() > 500)) throw new IllegalArgumentException("지출 입력값이 올바르지 않습니다.");
        this.title = title.trim();
        this.expenseDate = date;
        this.amount = amount;
        this.category = category;
        this.memo = memo;
        this.updatedAt = now;
    }

    public void remove(Instant now) {
        if (paidAt != null) throw new IllegalStateException("완료한 지출은 삭제할 수 없습니다.");
        if (deletedAt == null) {
            deletedAt = now;
            updatedAt = now;
        }
    }
}
