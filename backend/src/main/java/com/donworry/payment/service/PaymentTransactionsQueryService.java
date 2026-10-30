package com.donworry.payment.service;

import com.donworry.payment.dto.PaymentTransactionResponse;
import com.donworry.payment.dto.PaymentTransactionsPageResponse;
import com.donworry.payment.dto.PaymentTransactionsQueryRequest;
import com.donworry.payment.repository.PaymentTransactionRow;
import com.donworry.payment.repository.PaymentTransactionsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 보안 직원 거래 내역 (#363) — 운영 조회(미처리 후보만)와 달리 모든 결제를 최신순으로 보여 준다
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentTransactionsQueryService {
    private final PaymentTransactionsRepository repository;

    public PaymentTransactionsPageResponse findPage(PaymentTransactionsQueryRequest query) {
        long total = repository.count(query);
        long pages = total / query.size() + (total % query.size() == 0 ? 0 : 1);
        return PaymentTransactionsPageResponse.builder()
                .items(repository.findPage(query).stream()
                        .map(PaymentTransactionsQueryService::item)
                        .toList())
                .page(query.page())
                .size(query.size())
                .totalElements(total)
                .totalPages(pages)
                .hasNext((long) query.page() + 1 < pages)
                .from(query.from())
                .to(query.to())
                .build();
    }

    private static PaymentTransactionResponse item(PaymentTransactionRow row) {
        var p = row.payment();
        var r = row.request();
        var item = PaymentTransactionResponse.builder()
                .paymentId(p.getPaymentId())
                .createdAt(p.getCreatedAt())
                .merchantId(p.getMerchantId())
                .customerRef(p.getCustomerRef())
                .orderRef(p.getOrderRef())
                .amount(p.getAmount())
                .paymentStatus(p.getStatus().name());
        if (r == null) return item.build();
        return item.attemptNo(r.getAttemptNo())
                .fdsResult(r.getEvaluationResult())
                .evaluationId(r.getEvaluationResult() == null ? null : r.getEvaluationId())
                .cardDisplayLabel(r.getCardDisplayLabel())
                .ipCountryCode(r.getIpCountryCode())
                .maskedIpAddress(PaymentSignalMasking.ipAddress(r.getIpAddress()))
                .maskedDeviceId(PaymentSignalMasking.deviceId(r.getDeviceId()))
                .maskedBuyerEmail(PaymentSignalMasking.email(r.getBuyerEmail()))
                .build();
    }
}
