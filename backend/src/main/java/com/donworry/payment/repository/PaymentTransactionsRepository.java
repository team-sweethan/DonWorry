package com.donworry.payment.repository;

import com.donworry.payment.dto.PaymentTransactionsQueryRequest;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.entity.PaymentRequest;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PaymentTransactionsRepository {
    private final EntityManager entityManager;
    // 요청 기록 없는 결제도 "모든 결제"에 남도록 left join. payment_requests는 결제당 최대 1행이라 행이 늘지 않는다
    private static final String FROM = " from Payment p left join PaymentRequest r on r.paymentId = p.paymentId ";
    // 결제 시각 최신순, 같은 시각이면 나중에 저장된 결제가 먼저 (#797). 결제 번호 순서는 동시 저장 때 시각 순서와 어긋날 수 있다
    // 색인: 전체 가맹점은 idx_payments_created, 가맹점을 고르면 idx_payments_merchant_created
    private static final String LATEST_FIRST = " order by p.createdAt desc, p.paymentId desc";

    public List<PaymentTransactionRow> findPage(PaymentTransactionsQueryRequest query) {
        var rows = entityManager.createQuery("select p, r" + FROM + where(query) + LATEST_FIRST, Object[].class);
        parameters(query).forEach(rows::setParameter);
        return rows.setFirstResult(query.page() * query.size()).setMaxResults(query.size()).getResultList().stream()
                .map(row -> new PaymentTransactionRow((Payment) row[0], (PaymentRequest) row[1]))
                .toList();
    }

    // CSV 내보내기 (#651) — 쪽 나눔 없이 최신순으로 limit행까지. 호출 쪽은 상한 + 1로 불러 넘침을 판단한다
    public List<PaymentTransactionRow> findForExport(PaymentTransactionsQueryRequest query, int limit) {
        var rows = entityManager.createQuery("select p, r" + FROM + where(query) + LATEST_FIRST, Object[].class);
        parameters(query).forEach(rows::setParameter);
        return rows.setMaxResults(limit).getResultList().stream()
                .map(row -> new PaymentTransactionRow((Payment) row[0], (PaymentRequest) row[1]))
                .toList();
    }

    // 거래 상세 1건 (#732) — 기간 조건 없이 결제 번호로만 찾는다
    public Optional<PaymentTransactionRow> findById(Long paymentId) {
        return entityManager
                .createQuery("select p, r" + FROM + " where p.paymentId = :paymentId", Object[].class)
                .setParameter("paymentId", paymentId)
                .getResultList()
                .stream()
                .findFirst()
                .map(row -> new PaymentTransactionRow((Payment) row[0], (PaymentRequest) row[1]));
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
        // payments 참조 칸은 DB 기본 비교(_ci)라 대소문자를 무시한다. 앞 조건은 색인용, collate가 정확히 일치를 판단한다
        if (q.orderRef() != null)
            where += " and p.orderRef = :orderRef and collate(p.orderRef as utf8mb4_bin) = :orderRef";
        if (q.customerRef() != null)
            where += " and p.customerRef = :customerRef and collate(p.customerRef as utf8mb4_bin) = :customerRef";
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
