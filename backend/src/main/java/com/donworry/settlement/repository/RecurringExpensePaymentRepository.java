package com.donworry.settlement.repository;

import com.donworry.settlement.entity.RecurringExpensePayment;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecurringExpensePaymentRepository extends JpaRepository<RecurringExpensePayment, Long> {
    Optional<RecurringExpensePayment> findByRecurringExpenseIdAndOccurrenceDate(Long id, LocalDate date);

    // 한 규칙은 하루 한 회차이므로 기간 내 완료일은 최대 366개다.
    @Query(
            "SELECT p.occurrenceDate FROM RecurringExpensePayment p WHERE p.merchantId=:merchant AND p.recurringExpenseId=:rule AND p.occurrenceDate BETWEEN :from AND :to")
    List<LocalDate> findCompletedDates(
            @Param("merchant") Long merchant,
            @Param("rule") Long rule,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    List<RecurringExpensePayment> findByMerchantIdAndPaidAtGreaterThanEqualAndPaidAtLessThan(
            Long merchant, Instant from, Instant until, Pageable page);
}
