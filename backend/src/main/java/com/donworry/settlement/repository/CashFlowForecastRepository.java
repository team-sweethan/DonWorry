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
                    "SELECT COALESCE(SUM(payout_amount),0) FROM merchant_payouts WHERE merchant_id=:merchant AND status='COMPLETED' AND completed_at>=:from AND completed_at<:until",
            nativeQuery = true)
    BigDecimal sumPaidOut(@Param("merchant") Long merchant, @Param("from") Instant from, @Param("until") Instant until);

    // 잔여액은 가변 값이므로 cutoff 이후 배분이 보이는 스냅샷에서도 그 이전 잔액을 복원한다.
    @Query(value = """
            SELECT COALESCE(SUM(a.adjustment_amount - COALESCE((
                SELECT SUM(x.applied_amount) FROM payout_refund_allocations x
                JOIN merchant_payouts p ON p.payout_id=x.payout_id
                WHERE x.adjustment_id=a.adjustment_id AND p.merchant_id=:merchant
                  AND p.status='COMPLETED' AND p.completed_at<:asOf
            ),0)),0)
            FROM settlement_refund_adjustments a
            JOIN merchant_payout_items i ON i.settlement_id=a.settlement_id
            JOIN merchant_payouts original ON original.payout_id=i.payout_id
            WHERE a.merchant_id=:merchant AND a.created_at<:asOf
              AND original.merchant_id=:merchant AND original.status='COMPLETED'
              AND original.completed_at<:asOf
              AND (a.status='PENDING' OR (a.status='APPLIED' AND a.disposition='RECOVERY'))
            """, nativeQuery = true)
    BigDecimal outstandingRecovery(@Param("merchant") Long merchant, @Param("asOf") Instant asOf);

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
            "SELECT p FROM MerchantPayout p WHERE p.merchantId=:merchant AND p.status=com.donworry.settlement.entity.PayoutStatus.COMPLETED AND p.completedAt>=:from AND p.completedAt<:until ORDER BY p.completedAt, p.payoutId")
    List<MerchantPayout> findPaidOut(
            @Param("merchant") Long merchant,
            @Param("from") Instant from,
            @Param("until") Instant until,
            Pageable page);

    @Query(
            "SELECT s FROM Settlement s WHERE s.merchantId=:merchant AND (s.status=com.donworry.settlement.entity.SettlementStatus.SCHEDULED OR (s.status=com.donworry.settlement.entity.SettlementStatus.COMPLETED AND s.completedAt>=:asOf)) AND NOT EXISTS (SELECT a.adjustmentId FROM SettlementRefundAdjustment a WHERE a.settlementId=s.settlementId AND a.merchantId=:merchant AND a.createdAt<:asOf) AND (s.scheduledAt<:until OR (s.scheduledAt IS NULL AND s.expectedSettlementAt<:until)) ORDER BY s.settlementId")
    List<Settlement> findScheduled(
            @Param("merchant") Long merchant,
            @Param("until") Instant until,
            @Param("asOf") Instant asOf,
            Pageable page);

    @Query(
            "SELECT e FROM PlannedExpense e WHERE e.merchantId=:merchant AND e.deletedAt IS NULL AND ((e.paidAt>=:from AND e.paidAt<:until) OR ((e.paidAt IS NULL OR e.paidAt>=:until) AND e.expenseDate>=:today AND e.expenseDate<=:to)) ORDER BY e.expenseId")
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
