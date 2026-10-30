package com.donworry.settlement.repository;

import com.donworry.settlement.entity.RecurringExpensePayment;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecurringExpensePaymentRepository extends JpaRepository<RecurringExpensePayment, Long> {
    Optional<RecurringExpensePayment> findByRecurringExpenseIdAndOccurrenceDate(Long id, LocalDate date);

    List<RecurringExpensePayment> findByMerchantIdAndOccurrenceDateBetween(
            Long merchant, LocalDate from, LocalDate to, Pageable page);

    List<RecurringExpensePayment> findByMerchantIdAndPaidAtGreaterThanEqualAndPaidAtLessThan(
            Long merchant, Instant from, Instant until, Pageable page);
}
