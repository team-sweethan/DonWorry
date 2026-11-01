package com.donworry.settlement.repository;

import com.donworry.settlement.entity.RecurringExpense;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RecurringExpenseRepository extends JpaRepository<RecurringExpense, Long> {
    List<RecurringExpense> findAllByMerchantIdOrderByRecurringExpenseIdAsc(Long merchant);

    @Query(
            "SELECT e FROM RecurringExpense e WHERE e.merchantId=:merchant AND e.startDate<=:to AND (e.endDate IS NULL OR e.endDate>=:from) AND (e.stoppedFrom IS NULL OR e.stoppedFrom>:from)")
    List<RecurringExpense> findApplicable(
            @Param("merchant") Long merchant, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM RecurringExpense e WHERE e.recurringExpenseId=:id AND e.merchantId=:merchant")
    Optional<RecurringExpense> findOwnedForUpdate(@Param("id") Long id, @Param("merchant") Long merchant);
}
