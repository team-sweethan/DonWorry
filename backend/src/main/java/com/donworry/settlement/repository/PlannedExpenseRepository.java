package com.donworry.settlement.repository;

import com.donworry.settlement.entity.PlannedExpense;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PlannedExpenseRepository extends JpaRepository<PlannedExpense, Long> {
    List<PlannedExpense> findAllByMerchantIdAndDeletedAtIsNullAndExpenseDateBetweenOrderByExpenseDateAscExpenseIdAsc(
            Long merchantId, LocalDate from, LocalDate to);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM PlannedExpense e WHERE e.expenseId = :id AND e.merchantId = :merchant")
    Optional<PlannedExpense> findOwnedForUpdate(@Param("id") Long id, @Param("merchant") Long merchant);

    @Query("""
            SELECT e FROM PlannedExpense e
            WHERE e.merchantId = :merchant AND e.deletedAt IS NULL
              AND ((e.paidAt IS NULL AND e.expenseDate >= :fromDate AND e.expenseDate <= :toDate)
                OR (e.paidAt >= :fromInstant AND e.paidAt < :untilInstant))
            ORDER BY e.expenseDate, e.expenseId
            """)
    List<PlannedExpense> findForPeriod(
            @Param("merchant") Long merchant,
            @Param("fromDate") LocalDate from,
            @Param("toDate") LocalDate to,
            @Param("fromInstant") Instant fromInstant,
            @Param("untilInstant") Instant untilInstant);
}
