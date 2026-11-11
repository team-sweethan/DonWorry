package com.donworry.payment.service;

import com.donworry.fds.api.StaffCaseScope;
import com.donworry.fds.api.StaffCaseScopeReader;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.MerchantDirectoryReader;
import com.donworry.identity.api.StaffAccess;
import com.donworry.identity.api.StaffAccessDenyReason;
import com.donworry.identity.api.StaffAccessRecorder;
import com.donworry.identity.api.StaffAccessType;
import com.donworry.identity.api.StaffSummary;
import com.donworry.payment.dto.PaymentTransactionDetailResponse;
import com.donworry.payment.dto.PaymentTransactionResponse;
import com.donworry.payment.dto.PaymentTransactionsPageResponse;
import com.donworry.payment.dto.PaymentTransactionsQueryRequest;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.repository.PaymentTransactionRow;
import com.donworry.payment.repository.PaymentTransactionsRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 보안 직원 거래 내역 (#363) — 운영 조회(미처리 후보만)와 달리 모든 결제를 최신순으로 보여 준다
// 거래 상세는 보안 직원이면 담당 사건 고객의 결제만 연다 (#732, ADR-0082 3.3). ADMIN은 그대로(#730 결정)
@Service
@Transactional(readOnly = true)
public class PaymentTransactionsQueryService {
    private static final String SECURITY_STAFF = "SECURITY_STAFF";

    private final PaymentTransactionsRepository repository;
    private final StaffAccessRecorder accessRecorder;
    private final MerchantDirectoryReader merchantDirectory;
    private final CurrentStaff currentStaff;
    private final StaffCaseScopeReader scopeReader;
    private final Duration scopeClosedLookback;

    public PaymentTransactionsQueryService(
            PaymentTransactionsRepository repository,
            StaffAccessRecorder accessRecorder,
            MerchantDirectoryReader merchantDirectory,
            CurrentStaff currentStaff,
            StaffCaseScopeReader scopeReader,
            // 담당 범위는 내보내기 · INS-08 · 09와 같은 기준(열린 담당 사건 + 이 기간 안에 닫힌 담당 사건)
            @Value("${donworry.insider.scope-closed-lookback:30d}") Duration scopeClosedLookback) {
        this.repository = repository;
        this.accessRecorder = accessRecorder;
        this.merchantDirectory = merchantDirectory;
        this.currentStaff = currentStaff;
        this.scopeReader = scopeReader;
        this.scopeClosedLookback = scopeClosedLookback;
    }

    // 읽기 전용이 아니다 — 조회 기록을 같은 트랜잭션에 저장해, 기록이 실패하면 목록도 내보내지 않는다 (ADR-0082 4절 · #615)
    @Transactional
    public PaymentTransactionsPageResponse findPage(PaymentTransactionsQueryRequest query) {
        long total = repository.count(query);
        long pages = total / query.size() + (total % query.size() == 0 ? 0 : 1);
        // 줄마다 상세를 열 수 있는지 미리 알려 막다른 클릭을 없앤다. 판정은 상세 조회가 다시 한다
        Predicate<Payment> detailAllowed = detailAllowed();
        var page = PaymentTransactionsPageResponse.builder()
                .items(repository.findPage(query).stream()
                        .map(row -> item(row, detailAllowed.test(row.payment())))
                        .toList())
                .page(query.page())
                .size(query.size())
                .totalElements(total)
                .totalPages(pages)
                .hasNext((long) query.page() + 1 < pages)
                .from(query.from())
                .to(query.to())
                .build();
        accessRecorder.recordAllowed(StaffAccess.builder()
                .accessType(StaffAccessType.TRANSACTION_LIST_VIEW)
                .merchantId(knownMerchant(query.merchantId()))
                .customerRef(StaffAccess.isCustomerRef(query.customerRef()) ? query.customerRef() : null)
                .itemCount(page.items().size())
                .build());
        return page;
    }

    // 거래 상세 1건 (#732) — 열 때마다 허용 기록을 같은 트랜잭션에 남긴다. 기록이 실패하면 상세도 내보내지 않는다 (ADR-0082 4절)
    // 보안 직원은 담당 사건 고객의 결제만 연다. 범위 밖이면 거부 기록(독립 트랜잭션)을 남기고 막는다 — INS-01이 고객 단위로 센다
    // 보류 결제는 생기면 바로 사건이 열리고 배정되어(CaseLinkService) 담당 밖 결제를 볼 업무가 없다
    @Transactional
    public PaymentTransactionDetailResponse findDetail(Long paymentId) {
        PaymentTransactionRow row =
                repository.findById(paymentId).orElseThrow(PaymentTransactionNotFoundException::new);
        var payment = row.payment();
        var access = StaffAccess.builder()
                .accessType(StaffAccessType.TRANSACTION_DETAIL_VIEW)
                .paymentId(payment.getPaymentId())
                .merchantId(knownMerchant(payment.getMerchantId()))
                .customerRef(StaffAccess.isCustomerRef(payment.getCustomerRef()) ? payment.getCustomerRef() : null)
                .build();
        if (!detailAllowed().test(payment)) {
            accessRecorder.recordDenied(access, StaffAccessDenyReason.TRANSACTION_DETAIL_OUT_OF_SCOPE);
            throw new TransactionDetailOutOfScopeException();
        }
        accessRecorder.recordAllowed(access);
        return detail(row);
    }

    // 보안 직원: 담당 범위의 (가맹점, 고객)인 결제만. ADMIN: 모두. 로그인 직원이 없으면 열지 않는다
    private Predicate<Payment> detailAllowed() {
        Optional<StaffSummary> staff = currentStaff.findCurrent();
        if (staff.isEmpty()) return payment -> false;
        if (!SECURITY_STAFF.equals(staff.get().role())) return payment -> true;
        StaffCaseScope scope =
                scopeReader.findScope(staff.get().userId(), Instant.now().minus(scopeClosedLookback));
        return payment -> scope.hasCustomer(payment.getMerchantId(), payment.getCustomerRef());
    }

    // 기록 표의 가맹점 칸은 merchants FK다 — 없는 가맹점으로 거른 조회(빈 목록)가 기록 실패로 막히지 않게 가맹점 없이 남긴다
    private Long knownMerchant(Long merchantId) {
        if (merchantId == null) return null;
        return merchantDirectory.findAll().stream().anyMatch(m -> merchantId.equals(m.merchantId()))
                ? merchantId
                : null;
    }

    static PaymentTransactionResponse item(PaymentTransactionRow row, boolean detailAllowed) {
        var p = row.payment();
        var r = row.request();
        var item = PaymentTransactionResponse.builder()
                .paymentId(p.getPaymentId())
                .createdAt(p.getCreatedAt())
                .merchantId(p.getMerchantId())
                .customerRef(p.getCustomerRef())
                .orderRef(p.getOrderRef())
                .amount(p.getAmount())
                .paymentStatus(p.getStatus().name())
                .detailAllowed(detailAllowed);
        if (r == null) return item.build();
        return item.attemptNo(r.getAttemptNo())
                .fdsResult(r.getEvaluationResult())
                .evaluationId(r.getEvaluationResult() == null ? null : r.getEvaluationId())
                .build();
    }

    // 거래 상세와 CSV 내보내기가 같은 칸 · 같은 가림을 쓴다 (#651 · #732)
    static PaymentTransactionDetailResponse detail(PaymentTransactionRow row) {
        var p = row.payment();
        var r = row.request();
        var detail = PaymentTransactionDetailResponse.builder()
                .paymentId(p.getPaymentId())
                .createdAt(p.getCreatedAt())
                .merchantId(p.getMerchantId())
                .customerRef(p.getCustomerRef())
                .orderRef(p.getOrderRef())
                .amount(p.getAmount())
                .paymentStatus(p.getStatus().name());
        if (r == null) return detail.build();
        return detail.attemptNo(r.getAttemptNo())
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
