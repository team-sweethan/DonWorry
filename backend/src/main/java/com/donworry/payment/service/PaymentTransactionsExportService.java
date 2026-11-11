package com.donworry.payment.service;

import com.donworry.fds.api.InsiderExportGuard;
import com.donworry.fds.api.StaffCaseScopeReader;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.MerchantDirectoryEntry;
import com.donworry.identity.api.MerchantDirectoryReader;
import com.donworry.identity.api.StaffAccess;
import com.donworry.identity.api.StaffAccessDenyReason;
import com.donworry.identity.api.StaffAccessRecorder;
import com.donworry.identity.api.StaffAccessType;
import com.donworry.identity.api.StaffSummary;
import com.donworry.payment.dto.PaymentTransactionsQueryRequest;
import com.donworry.payment.repository.PaymentTransactionRow;
import com.donworry.payment.repository.PaymentTransactionsRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 거래 내역 CSV 내보내기 (#651, ADR-0082 3.1) — 화면 목록과 같은 조건 · 칸, 원문 없음, 최대 행 수 제한
// 보안 직원은 먼저 문지기(fds InsiderExportGuard)에 묻는다. 열린 경보면 거기서 경보 · 계정 정지까지 끝나고 여기서는 거부 기록 후 차단
// 그다음 담당 사건 범위를 본다(#730): 가맹점 + 고객 번호로 거른 조건이고 그 고객이 담당 범위일 때만 내보낸다
// ADMIN은 내부자 탐지 대상이 아니라 문지기 · 범위 모두 묻지 않는다 (11/09 결정: 관리자 동작은 그대로)
@Service
public class PaymentTransactionsExportService {
    private static final String SECURITY_STAFF = "SECURITY_STAFF";

    private final PaymentTransactionsRepository repository;
    private final StaffAccessRecorder accessRecorder;
    private final CurrentStaff currentStaff;
    private final MerchantDirectoryReader merchantDirectory;
    private final InsiderExportGuard exportGuard;
    private final StaffCaseScopeReader scopeReader;
    private final Duration scopeClosedLookback;
    private final int maxRows;

    public PaymentTransactionsExportService(
            PaymentTransactionsRepository repository,
            StaffAccessRecorder accessRecorder,
            CurrentStaff currentStaff,
            MerchantDirectoryReader merchantDirectory,
            InsiderExportGuard exportGuard,
            StaffCaseScopeReader scopeReader,
            // 담당 범위는 INS-08 · 09와 같은 기준(열린 담당 사건 + 이 기간 안에 닫힌 담당 사건)을 쓴다
            @Value("${donworry.insider.scope-closed-lookback:30d}") Duration scopeClosedLookback,
            @Value("${donworry.payment-transactions.export-max-rows:1000}") int maxRows) {
        if (maxRows < 1) throw new IllegalStateException("donworry.payment-transactions.export-max-rows는 1 이상이어야 합니다");
        this.repository = repository;
        this.accessRecorder = accessRecorder;
        this.currentStaff = currentStaff;
        this.merchantDirectory = merchantDirectory;
        this.exportGuard = exportGuard;
        this.scopeReader = scopeReader;
        this.scopeClosedLookback = scopeClosedLookback;
        this.maxRows = maxRows;
    }

    // 읽기 전용이 아니다 — 허용 기록을 같은 트랜잭션에 저장해, 기록이 실패하면 파일도 내보내지 않는다 (ADR-0082 4절)
    // 문지기 · 범위 검사는 데이터 조회보다 먼저다: 막히면 거래 행을 한 줄도 읽지 않는다. 상한 초과보다도 먼저다(시도 자체가 신호)
    // 문지기가 범위보다 먼저다 — 높음 경보 중이면 범위 밖 시도도 차단 · 정지로 이어진다
    @Transactional
    public String exportCsv(PaymentTransactionsQueryRequest query) {
        StaffSummary staff = currentStaff.findCurrent().orElseThrow(() -> new IllegalStateException("로그인한 직원이 없습니다"));
        Map<Long, String> merchantNames = merchantDirectory.findAll().stream()
                .collect(Collectors.toMap(
                        MerchantDirectoryEntry::merchantId, MerchantDirectoryEntry::displayName, (a, b) -> a));
        // 기록 표의 가맹점 칸은 merchants FK다 — 없는 가맹점으로 거른 요청이 기록 실패로 막히지 않게 가맹점 없이 남긴다
        Long merchantId =
                query.merchantId() != null && merchantNames.containsKey(query.merchantId()) ? query.merchantId() : null;
        var access = StaffAccess.builder()
                .accessType(StaffAccessType.TRANSACTION_EXPORT)
                .merchantId(merchantId)
                .customerRef(StaffAccess.isCustomerRef(query.customerRef()) ? query.customerRef() : null);
        if (SECURITY_STAFF.equals(staff.role())) {
            if (!exportGuard.allowExport(staff.userId())) {
                accessRecorder.recordDenied(access.build(), StaffAccessDenyReason.INSIDER_ALERT_OPEN);
                throw new InsiderExportBlockedException();
            }
            if (!inCaseScope(staff.userId(), query)) {
                accessRecorder.recordDenied(access.build(), StaffAccessDenyReason.EXPORT_OUT_OF_SCOPE);
                throw new ExportOutOfScopeException();
            }
        }
        List<PaymentTransactionRow> rows = repository.findForExport(query, maxRows + 1);
        if (rows.size() > maxRows) throw new ExportTooLargeException(maxRows);
        String csv = PaymentTransactionsCsv.write(
                rows.stream().map(PaymentTransactionsQueryService::detail).toList(), merchantNames);
        accessRecorder.recordAllowed(access.itemCount(rows.size()).build());
        return csv;
    }

    // 가맹점과 고객 번호를 모두 걸어야 한다 — 주문번호만 · 기간만으로 거르면 기록에 고객이 남지 않아 탐지가 세지 못한다 (#712)
    private boolean inCaseScope(Long staffId, PaymentTransactionsQueryRequest query) {
        if (query.merchantId() == null || query.customerRef() == null) return false;
        return scopeReader
                .findScope(staffId, Instant.now().minus(scopeClosedLookback))
                .hasCustomer(query.merchantId(), query.customerRef());
    }
}
