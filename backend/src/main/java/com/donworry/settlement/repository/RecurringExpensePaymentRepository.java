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

    interface CompletedOccurrence {
        Long getRecurringExpenseId();

        LocalDate getOccurrenceDate();
    }

    // ID 묶음과 최대 366일 범위로 완료 키만 읽어 회차별 쿼리와 전체 Entity 적재를 피한다.
    @Query(
            "SELECT p.recurringExpenseId AS recurringExpenseId, p.occurrenceDate AS occurrenceDate FROM RecurringExpensePayment p WHERE p.merchantId=:merchant AND p.recurringExpenseId IN :rules AND p.occurrenceDate BETWEEN :from AND :to")
    List<CompletedOccurrence> findCompletedOccurrences(
            @Param("merchant") Long merchant,
            @Param("rules") Collection<Long> rules,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    List<RecurringExpensePayment> findByMerchantIdAndPaidAtGreaterThanEqualAndPaidAtLessThan(
            Long merchant, Instant from, Instant until, Pageable page);
}
