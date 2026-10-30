package com.donworry.payment.repository;

import com.donworry.payment.dto.PaymentTransactionsQueryRequest;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.entity.PaymentRequest;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PaymentTransactionsRepository {
    private final EntityManager entityManager;
    // 요청 기록 없는 결제도 "모든 결제"에 남도록 left join. payment_requests는 결제당 최대 1행이라 행이 늘지 않는다
    private static final String FROM = " from Payment p left join PaymentRequest r on r.paymentId = p.paymentId ";

    public List<PaymentTransactionRow> findPage(PaymentTransactionsQueryRequest query) {
        // payment_id는 생성 순서와 같고 PK라 created_at 색인 없이도 최신순 정렬이 싸다
        var rows = entityManager.createQuery(
                "select p, r" + FROM + where(query) + " order by p.paymentId desc", Object[].class);
        parameters(query).forEach(rows::setParameter);
        return rows.setFirstResult(query.page() * query.size()).setMaxResults(query.size()).getResultList().stream()
                .map(row -> new PaymentTransactionRow((Payment) row[0], (PaymentRequest) row[1]))
                .toList();
    }

    public long count(PaymentTransactionsQueryRequest query) {
        var count = entityManager.createQuery("select count(p)" + FROM + where(query), Long.class);
        parameters(query).forEach(count::setParameter);
        return count.getSingleResult();
    }

    private String where(PaymentTransactionsQueryRequest q) {
        String where = " where p.createdAt >= :from and p.createdAt < :to";
        if (q.merchantId() != null) where += " and p.merchantId = :merchantId";
        if (q.status() != null) where += " and p.status = :status";
        if (q.orderRef() != null) where += " and p.orderRef = :orderRef";
        if (q.customerRef() != null) where += " and p.customerRef = :customerRef";
        return where;
    }

    private Map<String, Object> parameters(PaymentTransactionsQueryRequest q) {
        var values = new HashMap<String, Object>();
        values.put("from", q.from());
        values.put("to", q.to());
        if (q.merchantId() != null) values.put("merchantId", q.merchantId());
        if (q.status() != null) values.put("status", q.status());
        if (q.orderRef() != null) values.put("orderRef", q.orderRef());
        if (q.customerRef() != null) values.put("customerRef", q.customerRef());
        return values;
    }
}
