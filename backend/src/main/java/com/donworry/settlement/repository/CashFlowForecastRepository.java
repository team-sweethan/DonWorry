package com.donworry.settlement.repository;

import com.donworry.settlement.entity.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface CashFlowForecastRepository extends Repository<Settlement, Long> {
    // SQL SUM은 DECIMAL로 받아 BIGINT/브라우저 안전 정수 범위를 검사한 뒤 반환한다.
    @Query(
            value =
                    "SELECT COALESCE(SUM(settlement_amount),0) FROM settlements WHERE merchant_id=:merchant AND status='COMPLETED' AND completed_at>=:from AND completed_at<:until",
            nativeQuery = true)
    BigDecimal sumCompleted(
            @Param("merchant") Long merchant, @Param("from") Instant from, @Param("until") Instant until);

    @Query(
            value =
                    "SELECT COALESCE(SUM(amount),0) FROM planned_expenses WHERE merchant_id=:merchant AND deleted_at IS NULL AND paid_at>=:from AND paid_at<:until",
            nativeQuery = true)
    BigDecimal sumPaidExpenses(
            @Param("merchant") Long merchant, @Param("from") Instant from, @Param("until") Instant until);

    @Query(
            value =
                    "SELECT COALESCE(SUM(amount),0) FROM recurring_expense_payments WHERE merchant_id=:merchant AND paid_at>=:from AND paid_at<:until",
            nativeQuery = true)
    BigDecimal sumPaidRecurring(
            @Param("merchant") Long merchant, @Param("from") Instant from, @Param("until") Instant until);

    @Query(
            value =
                    "SELECT COALESCE(SUM(settlement_amount),0) FROM settlements WHERE merchant_id=:merchant AND status='SCHEDULED' AND scheduled_at IS NULL AND expected_settlement_at IS NULL",
            nativeQuery = true)
    BigDecimal sumUndated(@Param("merchant") Long merchant);

    @Query(
            value =
                    "SELECT COALESCE(SUM(settlement_amount),0) FROM settlements WHERE merchant_id=:merchant AND status='CANCELED'",
            nativeQuery = true)
    BigDecimal sumCanceled(@Param("merchant") Long merchant);

    @Query(
            "SELECT s FROM Settlement s WHERE s.merchantId=:merchant AND s.status=com.donworry.settlement.entity.SettlementStatus.COMPLETED AND s.completedAt>=:from AND s.completedAt<:until ORDER BY s.settlementId")
    List<Settlement> findCompleted(
            @Param("merchant") Long merchant,
            @Param("from") Instant from,
            @Param("until") Instant until,
            Pageable page);

    @Query(
            "SELECT s FROM Settlement s WHERE s.merchantId=:merchant AND s.status=com.donworry.settlement.entity.SettlementStatus.SCHEDULED AND (s.scheduledAt<:until OR (s.scheduledAt IS NULL AND s.expectedSettlementAt<:until)) ORDER BY s.settlementId")
    List<Settlement> findScheduled(@Param("merchant") Long merchant, @Param("until") Instant until, Pageable page);

    @Query(
            "SELECT e FROM PlannedExpense e WHERE e.merchantId=:merchant AND e.deletedAt IS NULL AND ((e.paidAt>=:from AND e.paidAt<:until) OR (e.paidAt IS NULL AND e.expenseDate>=:today AND e.expenseDate<=:to)) ORDER BY e.expenseId")
    List<PlannedExpense> findExpenses(
            @Param("merchant") Long merchant,
            @Param("from") Instant from,
            @Param("until") Instant until,
            @Param("today") LocalDate today,
            @Param("to") LocalDate to,
            Pageable page);

    @Query(
            "SELECT p FROM RecurringExpensePayment p WHERE p.merchantId=:merchant AND p.paidAt>=:from AND p.paidAt<:until ORDER BY p.paymentId")
    List<RecurringExpensePayment> findPaidRecurring(
            @Param("merchant") Long merchant,
            @Param("from") Instant from,
            @Param("until") Instant until,
            Pageable page);
}
