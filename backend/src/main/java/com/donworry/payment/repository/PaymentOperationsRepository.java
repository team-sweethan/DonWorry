package com.donworry.payment.repository;

import com.donworry.payment.dto.*;
import com.donworry.payment.entity.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PaymentOperationsRepository {
    private final EntityManager entityManager;
    private static final String FROM = " from Payment p left join PaymentRequest r on r.paymentId = p.paymentId"
            + " left join PaymentAction a on a.paymentId = p.paymentId ";
    private static final String CANDIDATE = "(p.status in ('PENDING', 'HELD') or a.status in ('PENDING', 'FAILED'))";
    // 동일 CASE를 목록 필터·count·상세에 사용해 페이지를 자른 뒤 분류가 달라지는 일을 막는다.
    private static final String CATEGORY = """
        case
        when r.requestId is not null and (
            r.merchantId <> p.merchantId
            or (r.processingStage = 'RECEIVED' and (r.evaluationId is not null or r.evaluationResult is not null
                or r.evaluationRiskScore is not null or r.evaluationErrorCode is not null or r.evaluatedAt is not null
                or p.status <> 'PENDING' or p.authorizationRef is not null or a.actionId is not null))
            or (r.processingStage <> 'RECEIVED' and (
                r.evaluationId is null or r.evaluationId <= 0 or r.evaluatedAt is null
                or coalesce(r.evaluationResult, '') not in ('ALLOW', 'REVIEW', 'DENY', 'ERROR')
                or (r.evaluationResult = 'ERROR' and (r.evaluationRiskScore is not null
                    or trim(coalesce(r.evaluationErrorCode, '')) = '' or r.processingStage <> 'NEEDS_REVIEW'
                    or p.status <> 'PENDING' or p.authorizationRef is not null or a.actionId is not null))
                or (r.evaluationResult <> 'ERROR' and (r.evaluationRiskScore is null or r.evaluationRiskScore < 0
                    or r.evaluationErrorCode is not null or r.processingStage = 'NEEDS_REVIEW'))
                or (r.processingStage = 'EVALUATED' and (p.status <> 'PENDING'
                    or p.authorizationRef is not null or a.actionId is not null))
                or (r.processingStage = 'AUTHORIZED' and not (
                    (r.evaluationResult = 'DENY' and p.status = 'DENIED' and p.authorizationRef is null and a.actionId is null)
                    or (r.evaluationResult in ('ALLOW', 'REVIEW') and p.status = 'DECLINED' and p.authorizationRef is null and a.actionId is null)
                    or (r.evaluationResult = 'ALLOW' and p.status in ('PENDING', 'CAPTURED') and p.authorizationRef is not null and a.actionId is null)
                    or (r.evaluationResult = 'REVIEW' and p.status in ('HELD', 'CAPTURED', 'VOIDED', 'EXPIRED') and p.authorizationRef is not null)
                ))
            ))
        ) then 'UNKNOWN'
        when a.actionId is not null and (
            (a.status = 'PENDING' and (a.completedAt is not null or a.captureId is not null or a.errorCode is not null))
            or (a.status = 'SUCCEEDED' and (a.completedAt is null or a.errorCode is not null
                or (a.actionType = 'CAPTURE' and (a.captureId is null or a.captureId <= 0))
                or (a.actionType = 'VOID' and a.captureId is not null)))
            or (a.status = 'FAILED' and (a.completedAt is null or trim(coalesce(a.errorCode, '')) = '' or a.captureId is not null))
        ) then 'UNKNOWN'
        when p.status = 'PENDING' and p.authorizationRef is null and a.actionId is null
            and r.processingStage = 'NEEDS_REVIEW' and r.evaluationResult = 'ERROR' then 'EVALUATION_ERROR'
        when p.status = 'PENDING' and p.authorizationRef is null and a.actionId is null
            and r.processingStage in ('RECEIVED', 'EVALUATED') then 'EVALUATION_PENDING'
        when p.status = 'PENDING' and p.authorizationRef is not null and a.actionId is null then 'AUTO_CAPTURE_PENDING'
        when p.status = 'HELD' and p.authorizationRef is not null and a.actionId is null then 'DECISION_PENDING'
        when p.status = 'HELD' and p.authorizationRef is not null and a.actionType = 'CAPTURE'
            and a.status = 'PENDING' then 'STAFF_CAPTURE_PENDING'
        when p.status = 'HELD' and p.authorizationRef is not null and a.actionType = 'VOID'
            and a.status = 'FAILED' and a.errorCode = 'VOID_DECLINED' then 'VOID_FAILED'
        when p.authorizationRef is not null and (
            (p.status = 'CAPTURED' and (a.actionId is null or (a.actionType = 'CAPTURE' and a.status = 'SUCCEEDED')))
            or (p.status = 'VOIDED' and (a.actionId is null or (a.actionType = 'VOID' and a.status = 'SUCCEEDED')))
        ) then 'FINANCIAL_COMPLETED'
        when a.actionId is null and (
            (p.status in ('DENIED', 'DECLINED') and p.authorizationRef is null)
            or (p.status = 'EXPIRED' and p.authorizationRef is not null)
        ) then 'PAYMENT_TERMINATED'
        else 'UNKNOWN' end
        """;

    public List<PaymentOperationRow> findPage(PaymentOperationsQueryRequest query) {
        return rows(where(query), parameters(query))
                .setFirstResult(query.page() * query.size())
                .setMaxResults(query.size())
                .getResultList()
                .stream()
                .map(this::row)
                .toList();
    }

    public long count(PaymentOperationsQueryRequest query) {
        var count = entityManager.createQuery("select count(p)" + FROM + where(query), Long.class);
        parameters(query).forEach(count::setParameter);
        return count.getSingleResult();
    }

    public Optional<PaymentOperationRow> findById(long paymentId) {
        return rows(" where p.paymentId = :paymentId", Map.of("paymentId", paymentId)).getResultList().stream()
                .findFirst()
                .map(this::row);
    }

    private TypedQuery<Object[]> rows(String where, Map<String, Object> parameters) {
        var query = entityManager.createQuery(
                "select p, r, a, " + CATEGORY + FROM + where + " order by p.createdAt asc, p.paymentId asc",
                Object[].class);
        parameters.forEach(query::setParameter);
        return query;
    }

    private PaymentOperationRow row(Object[] row) {
        return new PaymentOperationRow(
                (Payment) row[0],
                (PaymentRequest) row[1],
                (PaymentAction) row[2],
                PaymentOperationCategory.valueOf((String) row[3]));
    }

    private String where(PaymentOperationsQueryRequest q) {
        String where = " where " + CANDIDATE;
        if (q.paymentId() != null) where += " and p.paymentId = :paymentId";
        if (q.merchantId() != null) where += " and p.merchantId = :merchantId";
        if (q.orderRef() != null) where += " and p.orderRef = :orderRef";
        if (q.paymentStatus() != null) where += " and p.status = :paymentStatus";
        if (q.category() != null) where += " and (" + CATEGORY + ") = :category";
        if (q.createdFrom() != null) where += " and p.createdAt >= :createdFrom";
        if (q.createdTo() != null) where += " and p.createdAt < :createdTo";
        return where;
    }

    private Map<String, Object> parameters(PaymentOperationsQueryRequest q) {
        var values = new HashMap<String, Object>();
        if (q.paymentId() != null) values.put("paymentId", q.paymentId());
        if (q.merchantId() != null) values.put("merchantId", q.merchantId());
        if (q.orderRef() != null) values.put("orderRef", q.orderRef());
        if (q.paymentStatus() != null) values.put("paymentStatus", q.paymentStatus());
        if (q.category() != null) values.put("category", q.category().name());
        if (q.createdFrom() != null) values.put("createdFrom", q.createdFrom());
        if (q.createdTo() != null) values.put("createdTo", q.createdTo());
        return values;
    }
}
