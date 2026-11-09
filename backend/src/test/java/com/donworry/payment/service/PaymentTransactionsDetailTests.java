package com.donworry.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import com.donworry.payment.dto.PaymentTransactionResponse;
import com.donworry.payment.dto.PaymentTransactionsQueryRequest;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.repository.PaymentTransactionRow;
import com.donworry.payment.repository.PaymentTransactionsRepository;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

// 거래 상세 1건 (#732) — 승인된 테스트 목록만 (노트 issue-732 "승인된 테스트 목록")
// 기록 실패 시 상세를 내보내지 않는 것은 PaymentTransactionsRecordFailureTests가 실제 트랜잭션으로 확인한다
class PaymentTransactionsDetailTests {
    private static final Long STAFF = 4L;
    private static final Long MERCHANT = 11L;
    private static final Long PAYMENT = 501L;
    private static final String OWN_CUSTOMER = "11:5001";
    private static final String OTHER_CUSTOMER = "11:5002";
    private static final Instant NOW = Instant.parse("2026-11-09T01:00:00Z");

    private final PaymentTransactionsRepository repository = mock(PaymentTransactionsRepository.class);
    private final StaffAccessRecorder recorder = mock(StaffAccessRecorder.class);
    private final MerchantDirectoryReader merchants = mock(MerchantDirectoryReader.class);
    private final CurrentStaff currentStaff = mock(CurrentStaff.class);
    private final StaffCaseScopeReader scopeReader = mock(StaffCaseScopeReader.class);
    private final PaymentTransactionsQueryService service = new PaymentTransactionsQueryService(
            repository, recorder, merchants, currentStaff, scopeReader, Duration.ofDays(30));

    @BeforeEach
    void setUp() {
        when(merchants.findAll()).thenReturn(List.of(new MerchantDirectoryEntry(MERCHANT, "m-11", "케이크 가게", "ACTIVE")));
        // 이 직원의 담당 범위 = 가맹점 11의 고객 OWN_CUSTOMER 한 명
        when(scopeReader.findScope(eq(STAFF), any()))
                .thenReturn(new StaffCaseScope(
                        Set.of(MERCHANT), Set.of(new StaffCaseScope.CaseCustomer(MERCHANT, OWN_CUSTOMER))));
    }

    // 담당 고객 상세를 열어도 기록이 안 남으면 누가 어느 결제를 봤는지 알 수 없다
    @Test
    void detail_recordsDetailViewWithPaymentMerchantAndCustomer() {
        signIn("SECURITY_STAFF");
        givenPayment(PAYMENT, OWN_CUSTOMER);

        var detail = service.findDetail(PAYMENT);

        assertThat(detail.paymentId()).isEqualTo(PAYMENT);
        var access = ArgumentCaptor.forClass(StaffAccess.class);
        verify(recorder).recordAllowed(access.capture());
        assertThat(access.getValue().accessType()).isEqualTo(StaffAccessType.TRANSACTION_DETAIL_VIEW);
        assertThat(access.getValue().paymentId()).isEqualTo(PAYMENT);
        assertThat(access.getValue().merchantId()).isEqualTo(MERCHANT);
        assertThat(access.getValue().customerRef()).isEqualTo(OWN_CUSTOMER);
    }

    // 남의 사건 고객 결제가 열리거나, 막혀도 거부 기록이 없으면 INS-01이 반복 시도를 셀 수 없다
    @Test
    void detail_whenCustomerNotInStaffCaseScope_recordsDenialWithoutReturningPayment() {
        signIn("SECURITY_STAFF");
        givenPayment(PAYMENT, OTHER_CUSTOMER);

        assertThatThrownBy(() -> service.findDetail(PAYMENT)).isInstanceOf(TransactionDetailOutOfScopeException.class);

        var access = ArgumentCaptor.forClass(StaffAccess.class);
        verify(recorder).recordDenied(access.capture(), eq(StaffAccessDenyReason.TRANSACTION_DETAIL_OUT_OF_SCOPE));
        assertThat(access.getValue().accessType()).isEqualTo(StaffAccessType.TRANSACTION_DETAIL_VIEW);
        assertThat(access.getValue().paymentId()).isEqualTo(PAYMENT);
        assertThat(access.getValue().customerRef()).isEqualTo(OTHER_CUSTOMER);
        verify(recorder, never()).recordAllowed(any());
    }

    // 관리자가 상세를 못 보면 #730 "관리자 동작은 그대로" 결정과 어긋난다
    @Test
    void detail_whenAdmin_skipsScopeCheck() {
        signIn("ADMIN");
        givenPayment(PAYMENT, OTHER_CUSTOMER);

        var detail = service.findDetail(PAYMENT);

        assertThat(detail.paymentId()).isEqualTo(PAYMENT);
        verify(recorder).recordAllowed(any());
        verify(scopeReader, never()).findScope(anyLong(), any());
    }

    // 없는 번호에 500이 나거나, 열람하지 않은 결제가 기록에 쌓이면 안 된다
    @Test
    void detail_unknownPayment_returnsNotFoundWithoutRecord() {
        signIn("SECURITY_STAFF");
        when(repository.findById(PAYMENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findDetail(PAYMENT)).isInstanceOf(PaymentTransactionNotFoundException.class);

        verifyNoInteractions(recorder);
    }

    // 담당 고객 줄을 못 누르면 업무가 막히고, 막힐 줄이 눌리면 403 막다른 길이 된다
    @Test
    void list_marksDetailAllowedOnlyForInScopeCustomers() {
        signIn("SECURITY_STAFF");
        var query = PaymentTransactionsQueryRequest.builder()
                .from(NOW.minusSeconds(3600))
                .to(NOW)
                .page(0)
                .size(20)
                .build();
        when(repository.count(query)).thenReturn(2L);
        when(repository.findPage(query))
                .thenReturn(List.of(
                        new PaymentTransactionRow(payment(1L, OWN_CUSTOMER), null),
                        new PaymentTransactionRow(payment(2L, OTHER_CUSTOMER), null)));

        var items = service.findPage(query).items();

        assertThat(items)
                .extracting(PaymentTransactionResponse::paymentId, PaymentTransactionResponse::detailAllowed)
                .containsExactly(tuple(1L, true), tuple(2L, false));
    }

    // 목록이 다시 이메일 · 카드 · 접속 · 기기 값을 20줄씩 한꺼번에 내보내면 1건 열람 기록이 의미가 없다
    @Test
    void list_responseHasNoContactOrEnvironmentFields() {
        List<String> fields = Arrays.stream(PaymentTransactionResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(fields)
                .doesNotContain(
                        "cardDisplayLabel", "ipCountryCode", "maskedIpAddress", "maskedDeviceId", "maskedBuyerEmail");
    }

    private void signIn(String role) {
        when(currentStaff.findCurrent())
                .thenReturn(Optional.of(
                        StaffSummary.builder().userId(STAFF).role(role).build()));
    }

    private void givenPayment(Long paymentId, String customerRef) {
        when(repository.findById(paymentId))
                .thenReturn(Optional.of(new PaymentTransactionRow(payment(paymentId, customerRef), null)));
    }

    private static Payment payment(Long paymentId, String customerRef) {
        var payment = Payment.pending(MERCHANT, customerRef, "order-" + paymentId, 1000L, NOW.minusSeconds(60));
        ReflectionTestUtils.setField(payment, "paymentId", paymentId);
        return payment;
    }
}
