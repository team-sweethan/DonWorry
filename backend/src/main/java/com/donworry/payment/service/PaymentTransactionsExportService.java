package com.donworry.payment.service;

import com.donworry.fds.api.InsiderExportGuard;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 거래 내역 CSV 내보내기 (#651, ADR-0082 INS-05) — 화면 목록과 같은 조건 · 칸, 원문 없음, 최대 행 수 제한
// 보안 직원은 먼저 문지기(fds InsiderExportGuard)에 묻는다. 열린 경보면 거기서 경보 · 계정 정지까지 끝나고 여기서는 거부 기록 후 차단
// ADMIN은 내부자 탐지 대상이 아니라 묻지 않는다
@Service
public class PaymentTransactionsExportService {
    private static final String SECURITY_STAFF = "SECURITY_STAFF";

    private final PaymentTransactionsRepository repository;
    private final StaffAccessRecorder accessRecorder;
    private final CurrentStaff currentStaff;
    private final MerchantDirectoryReader merchantDirectory;
    private final InsiderExportGuard exportGuard;
    private final int maxRows;

    public PaymentTransactionsExportService(
            PaymentTransactionsRepository repository,
            StaffAccessRecorder accessRecorder,
            CurrentStaff currentStaff,
            MerchantDirectoryReader merchantDirectory,
            InsiderExportGuard exportGuard,
            @Value("${donworry.payment-transactions.export-max-rows:1000}") int maxRows) {
        if (maxRows < 1) throw new IllegalStateException("donworry.payment-transactions.export-max-rows는 1 이상이어야 합니다");
        this.repository = repository;
        this.accessRecorder = accessRecorder;
        this.currentStaff = currentStaff;
        this.merchantDirectory = merchantDirectory;
        this.exportGuard = exportGuard;
        this.maxRows = maxRows;
    }

    // 읽기 전용이 아니다 — 허용 기록을 같은 트랜잭션에 저장해, 기록이 실패하면 파일도 내보내지 않는다 (ADR-0082 4절)
    // 문지기 검사는 데이터 조회보다 먼저다: 막히면 거래 행을 한 줄도 읽지 않는다. 상한 초과보다도 먼저다(시도 자체가 신호)
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
        if (SECURITY_STAFF.equals(staff.role()) && !exportGuard.allowExport(staff.userId())) {
            accessRecorder.recordDenied(access.build(), StaffAccessDenyReason.INSIDER_ALERT_OPEN);
            throw new InsiderExportBlockedException();
        }
        List<PaymentTransactionRow> rows = repository.findForExport(query, maxRows + 1);
        if (rows.size() > maxRows) throw new ExportTooLargeException(maxRows);
        String csv = PaymentTransactionsCsv.write(
                rows.stream().map(PaymentTransactionsQueryService::item).toList(), merchantNames);
        accessRecorder.recordAllowed(access.itemCount(rows.size()).build());
        return csv;
    }
}
