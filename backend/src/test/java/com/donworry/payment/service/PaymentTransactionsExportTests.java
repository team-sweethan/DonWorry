package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.donworry.fds.api.InsiderExportGuard;
import com.donworry.fds.api.StaffCaseScope;
import com.donworry.fds.api.StaffCaseScopeReader;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.MerchantDirectoryEntry;
import com.donworry.identity.api.MerchantDirectoryReader;
import com.donworry.identity.api.StaffAccess;
import com.donworry.identity.api.StaffAccessDenyReason;
import com.donworry.identity.api.StaffAccessRecorder;
import com.donworry.identity.api.StaffAccessType;
import com.donworry.identity.api.StaffSummary;
import com.donworry.payment.dto.PaymentTransactionDetailResponse;
import com.donworry.payment.dto.PaymentTransactionsQueryRequest;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.repository.PaymentTransactionRow;
import com.donworry.payment.repository.PaymentTransactionsRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

// 거래 내역 CSV 내보내기 (#651 · #730) — 승인된 테스트 목록만 (노트 issue-651 · issue-730 "승인된 테스트 목록")
// 응답 롤백 뒤 정지 유지 · 세션 끊김은 PaymentTransactionsHttpMariaDbTests가 실제 DB로 확인한다
class PaymentTransactionsExportTests {
    private static final Long STAFF = 4L;
    private static final Long MERCHANT = 11L;
    private static final Instant NOW = Instant.parse("2026-11-08T01:00:00Z");
    private static final int MAX_ROWS = 3;
    private static final String OWN_CUSTOMER = "11:5001";
    private static final String OTHER_CUSTOMER = "11:5002";

    private final PaymentTransactionsRepository repository = mock(PaymentTransactionsRepository.class);
    private final StaffAccessRecorder recorder = mock(StaffAccessRecorder.class);
    private final CurrentStaff currentStaff = mock(CurrentStaff.class);
    private final MerchantDirectoryReader merchants = mock(MerchantDirectoryReader.class);
    private final InsiderExportGuard guard = mock(InsiderExportGuard.class);
    private final StaffCaseScopeReader scopeReader = mock(StaffCaseScopeReader.class);
    private final PaymentTransactionsExportService service = new PaymentTransactionsExportService(
            repository, recorder, currentStaff, merchants, guard, scopeReader, Duration.ofDays(30), MAX_ROWS);

    @BeforeEach
    void setUp() {
        when(merchants.findAll()).thenReturn(List.of(new MerchantDirectoryEntry(MERCHANT, "m-11", "케이크 가게", "ACTIVE")));
        // 이 직원의 담당 범위 = 가맹점 11의 고객 OWN_CUSTOMER 한 명
        when(scopeReader.findScope(eq(STAFF), any()))
                .thenReturn(new StaffCaseScope(
                        Set.of(MERCHANT), Set.of(new StaffCaseScope.CaseCustomer(MERCHANT, OWN_CUSTOMER))));
    }

    // 담당 아닌 고객으로 거른 내보내기가 통하면 남의 사건 고객 거래가 파일로 나간다 (#730)
    @Test
    void exportCsv_whenCustomerNotInStaffCaseScope_recordsDenialWithoutReadingRows() {
        signIn("SECURITY_STAFF");
        when(guard.allowExport(STAFF)).thenReturn(true);

        assertThatThrownBy(() -> service.exportCsv(query(OTHER_CUSTOMER)))
                .isInstanceOf(ExportOutOfScopeException.class);

        verifyNoInteractions(repository);
        var access = ArgumentCaptor.forClass(StaffAccess.class);
        verify(recorder).recordDenied(access.capture(), eq(StaffAccessDenyReason.EXPORT_OUT_OF_SCOPE));
        assertThat(access.getValue().accessType()).isEqualTo(StaffAccessType.TRANSACTION_EXPORT);
        assertThat(access.getValue().customerRef()).isEqualTo(OTHER_CUSTOMER);
        verify(recorder, never()).recordAllowed(any());
    }

    // 고객 조건 없이(기간 · 주문번호만) 내보내기가 통하면 한 번에 최대 상한까지 나가고, 기록에 고객이 없어 탐지도 못 센다 (#712)
    @Test
    void exportCsv_whenNoCustomerFilter_recordsDenialWithoutReadingRows() {
        signIn("SECURITY_STAFF");
        when(guard.allowExport(STAFF)).thenReturn(true);
        var orderOnly = PaymentTransactionsQueryRequest.builder()
                .from(NOW.minusSeconds(3600))
                .to(NOW)
                .merchantId(MERCHANT)
                .orderRef("order-1")
                .page(0)
                .size(20)
                .build();

        assertThatThrownBy(() -> service.exportCsv(orderOnly)).isInstanceOf(ExportOutOfScopeException.class);

        verifyNoInteractions(repository);
        verify(recorder).recordDenied(any(), eq(StaffAccessDenyReason.EXPORT_OUT_OF_SCOPE));
        verify(recorder, never()).recordAllowed(any());
    }

    // 담당 사건 고객 내보내기는 정상 업무다 — 막히면 사건 조사 자료를 꺼낼 수 없다
    @Test
    void exportCsv_whenCustomerInStaffCaseScope_exportsAndRecordsAllowed() {
        signIn("SECURITY_STAFF");
        when(guard.allowExport(STAFF)).thenReturn(true);
        when(repository.findForExport(any(), anyInt())).thenReturn(rows(2));

        String csv = service.exportCsv(query());

        assertThat(csv.split("\r\n")).hasSize(3);
        var access = ArgumentCaptor.forClass(StaffAccess.class);
        verify(recorder).recordAllowed(access.capture());
        assertThat(access.getValue().customerRef()).isEqualTo(OWN_CUSTOMER);
        assertThat(access.getValue().itemCount()).isEqualTo(2);
        verify(recorder, never()).recordDenied(any(), any());
    }

    // 열린 경보로 막히면 거래 행을 읽지 않고, 거부 기록(INSIDER_ALERT_OPEN)만 남기고 차단한다
    @Test
    void exportCsv_whenGuardBlocks_recordsDenialWithoutReadingRows() {
        signIn("SECURITY_STAFF");
        when(guard.allowExport(STAFF)).thenReturn(false);

        assertThatThrownBy(() -> service.exportCsv(query())).isInstanceOf(InsiderExportBlockedException.class);

        verifyNoInteractions(repository);
        var access = ArgumentCaptor.forClass(StaffAccess.class);
        verify(recorder).recordDenied(access.capture(), eq(StaffAccessDenyReason.INSIDER_ALERT_OPEN));
        assertThat(access.getValue().accessType()).isEqualTo(StaffAccessType.TRANSACTION_EXPORT);
        verify(recorder, never()).recordAllowed(any());
    }

    // ADMIN은 내부자 탐지 대상이 아니다 — 문지기를 부르지 않고 파일을 받는다
    @Test
    void exportCsv_whenAdmin_skipsGuardAndExports() {
        signIn("ADMIN");
        when(repository.findForExport(any(), anyInt())).thenReturn(rows(2));

        String csv = service.exportCsv(query());

        verifyNoInteractions(guard);
        assertThat(csv.split("\r\n")).hasSize(3);
        var access = ArgumentCaptor.forClass(StaffAccess.class);
        verify(recorder).recordAllowed(access.capture());
        assertThat(access.getValue().itemCount()).isEqualTo(2);
    }

    // 상한을 넘으면 잘라 주지 않고 거절한다 — 허용 기록도 남기지 않는다
    @Test
    void exportCsv_whenRowsExceedLimit_rejectsWithoutFile() {
        signIn("SECURITY_STAFF");
        when(guard.allowExport(STAFF)).thenReturn(true);
        when(repository.findForExport(any(), anyInt())).thenReturn(rows(MAX_ROWS + 1));

        assertThatThrownBy(() -> service.exportCsv(query())).isInstanceOf(ExportTooLargeException.class);

        verify(repository).findForExport(any(), eq(MAX_ROWS + 1));
        verify(recorder, never()).recordAllowed(any());
    }

    // 허용 기록 저장이 실패하면 파일을 돌려주지 않는다 (fail closed, ADR-0082 4절)
    @Test
    void exportCsv_whenAllowedRecordFails_returnsNoFile() {
        signIn("SECURITY_STAFF");
        when(guard.allowExport(STAFF)).thenReturn(true);
        when(repository.findForExport(any(), anyInt())).thenReturn(rows(1));
        doThrow(new IllegalStateException("기록 저장 실패")).when(recorder).recordAllowed(any());

        assertThatThrownBy(() -> service.exportCsv(query())).isInstanceOf(IllegalStateException.class);
    }

    // 목록에서 카드 · 접속 · 기기 · 이메일을 뺐어도(#732) CSV는 거래 상세와 같은 15칸을 유지한다. 주문자 이름 · 전화 · 원문 칸은 없다
    @Test
    void csv_keepsFullColumns() {
        assertThat(PaymentTransactionsCsv.headers())
                .containsExactly(
                        "결제번호",
                        "결제시각(KST)",
                        "가맹점번호",
                        "가맹점",
                        "고객번호",
                        "주문번호",
                        "시도",
                        "금액",
                        "결제상태",
                        "FDS결과",
                        "평가번호",
                        "카드",
                        "IP국가",
                        "IP(가림)",
                        "기기(가림)",
                        "회원이메일(가림)");
        // 상세 DTO에 칸이 늘면(원문이 섞일 수 있는 변화) 이 테스트가 CSV 칸을 다시 보게 만든다
        assertThat(PaymentTransactionDetailResponse.class.getRecordComponents()).hasSize(15);
        assertThat(String.join(",", PaymentTransactionsCsv.headers()))
                .doesNotContain("이름")
                .doesNotContain("전화");
    }

    // 엑셀이 수식으로 실행하는 글자(= + - @ 탭)로 시작하는 값은 앞에 '를 붙인다. 숫자 칸은 그대로 둔다
    @Test
    void csv_whenValueStartsWithFormulaChar_prefixesQuote() {
        var item = PaymentTransactionDetailResponse.builder()
                .paymentId(1L)
                .customerRef("=HYPERLINK(\"http://x\")")
                .orderRef("+1")
                .cardDisplayLabel("@SUM(A1)")
                .maskedBuyerEmail("-a@b")
                .ipCountryCode("\tKR")
                .amount(1000L)
                .build();

        String row = PaymentTransactionsCsv.write(List.of(item), Map.of()).split("\r\n")[1];

        assertThat(row)
                .contains("\"'=HYPERLINK(\"\"http://x\"\")\"")
                .contains(",'+1,")
                .contains(",'@SUM(A1),")
                .contains(",'-a@b")
                .contains(",'\tKR,")
                .contains(",1000,");
    }

    private void signIn(String role) {
        when(currentStaff.findCurrent())
                .thenReturn(Optional.of(
                        StaffSummary.builder().userId(STAFF).role(role).build()));
    }

    // 기본은 담당 범위 안 고객으로 거른 조건 — 화면은 "가맹점ID:번호"로 보낸다
    private static PaymentTransactionsQueryRequest query() {
        return query(OWN_CUSTOMER);
    }

    private static PaymentTransactionsQueryRequest query(String customerRef) {
        return PaymentTransactionsQueryRequest.builder()
                .from(NOW.minusSeconds(3600))
                .to(NOW)
                .merchantId(MERCHANT)
                .customerRef(customerRef)
                .page(0)
                .size(20)
                .build();
    }

    private static List<PaymentTransactionRow> rows(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new PaymentTransactionRow(
                        Payment.pending(MERCHANT, "cust-" + i, "order-" + i, 1000L, NOW.minusSeconds(60L + i)), null))
                .toList();
    }
}
